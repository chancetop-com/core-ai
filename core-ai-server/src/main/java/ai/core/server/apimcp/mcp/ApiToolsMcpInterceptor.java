package ai.core.server.apimcp.mcp;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.apitoolhub.ApiToolCatalogService;
import ai.core.server.apitoolhub.ApiToolHubAccessPolicy;
import ai.core.server.apitoolhub.ApiToolHubService;
import ai.core.server.apitoolhub.ApiToolTimeoutException;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPMethod;
import core.framework.inject.Inject;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Response;
import core.framework.web.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authorization and hardened handling for the legacy {@code /api/api-tools/mcp} streamable
 * MCP endpoint (bound in the core-ai module, so it cannot carry {@code @PermissionsRequired}):
 * the {@code tools/list} and {@code tools/call} JSON-RPC methods are fully handled here with
 * the API-Tool Hub semantics — {@code apitool.call} enforcement (JSON-RPC error -32001,
 * HTTP 200), per-app whitelisting of API users, caller-header injection and {@code hub_calls}
 * audit — instead of the previous zero-permission SDK path. All other JSON-RPC methods fall
 * through to the SDK transport, keeping the permission whitelist in {@code PermissionInterceptor}
 * as the protocol surface.
 *
 * @author stephen
 */
public class ApiToolsMcpInterceptor implements Interceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiToolsMcpInterceptor.class);
    private static final String ENDPOINT_PATH = "/api/api-tools/mcp";
    private static final String PERMISSION_DENIED = "permission denied: apitool.call is required to list or call API tools";

    @Inject
    PermissionService permissionService;
    @Inject
    ApiToolHubAccessPolicy accessPolicy;
    @Inject
    ApiToolCatalogService catalog;
    @Inject
    ApiToolHubService hubService;

    public boolean authDisabled;

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        if (!ENDPOINT_PATH.equals(request.path()) || request.method() != HTTPMethod.POST) {
            return invocation.proceed();
        }
        if (authDisabled) return invocation.proceed();
        var body = request.body().orElse(new byte[0]);
        if (body.length == 0) return invocation.proceed();   // let the controller answer 400

        Map<String, Object> message;
        try {
            message = JsonUtil.toMap(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return invocation.proceed();   // malformed JSON is the controller's business
        }
        if (!message.containsKey("id") || !message.containsKey("method")) return invocation.proceed();
        var method = String.valueOf(message.get("method"));
        if (!"tools/list".equals(method) && !"tools/call".equals(method)) return invocation.proceed();

        var userId = AuthContext.userId(invocation.context());
        var params = asParams(message.get("params"));
        try {
            var response = switch (method) {
                case "tools/list" -> listTools(userId, message.get("id"));
                case "tools/call" -> callTool(userId, params, message.get("id"));
                default -> null;
            };
            return jsonResponse(response);
        } catch (ApiToolTimeoutException e) {
            LOGGER.warn("api-tools mcp call timed out, userId={}, params={}", userId, params, e);
            return jsonResponse(error(message.get("id"), -32603, e.getMessage()));
        } catch (ForbiddenException e) {
            LOGGER.warn("api-tools mcp access denied, method={}, userId={}, message={}", method, userId, e.getMessage());
            return jsonResponse(error(message.get("id"), -32001, e.getMessage()));
        } catch (RuntimeException e) {
            LOGGER.warn("api-tools mcp request failed, method={}, userId={}", method, userId, e);
            return jsonResponse(error(message.get("id"), -32603, e.getMessage()));
        }
    }

    private Map<String, Object> listTools(String userId, Object id) {
        var operations = catalog.allOperations().stream()
                .filter(operation -> accessPolicy.canAccessApp(userId, operation.app()))
                .toList();
        var tools = operations.stream().map(operation -> {
            var tool = new LinkedHashMap<String, Object>();
            tool.put("name", operation.toolName());
            tool.put("description", operation.description());
            tool.put("inputSchema", JsonUtil.toMap(operation.inputSchemaJson()));
            return tool;
        }).toList();
        return result(id, Map.of("tools", tools));
    }

    private Map<String, Object> callTool(String userId, Map<String, Object> params, Object id) {
        String toolName = params.get("name") == null ? null : String.valueOf(params.get("name"));
        if (toolName == null || toolName.isBlank()) {
            return error(id, -32602, "missing tool name");
        }
        var entry = catalog.findByToolName(toolName);
        if (entry == null) {
            return error(id, -32602, "tool not found: " + toolName);
        }
        boolean apiUser = accessPolicy.isApiUser(userId);
        if (apiUser) {
            if (!accessPolicy.canAccessApp(userId, entry.app())) {
                return error(id, -32001, "permission denied: app not whitelisted: " + entry.app());
            }
        } else if (!permissionService.has(userId, PermissionCodes.APITOOL_CALL)) {
            return error(id, -32001, PERMISSION_DENIED);
        }

        var request = new HubCallRequest();
        var arguments = params.get("arguments");
        request.arguments = arguments == null ? "{}" : JsonUtil.toJson(arguments);
        HubCallResponse call = hubService.call(userId, "mcp", entry.app(), entry.service(), entry.name(), request);
        var text = call.text == null ? "" : call.text;
        var content = List.of(Map.of("type", "text", "text", text));
        return result(id, Map.of("content", content, "isError", Boolean.TRUE.equals(call.isError)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asParams(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return new HashMap<>();
    }

    private Map<String, Object> result(Object id, Map<String, Object> result) {
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return response;
    }

    private Response jsonResponse(Map<String, Object> response) {
        return Response.bytes(JsonUtil.toJson(response).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .contentType(ContentType.APPLICATION_JSON)
                .header("Access-Control-Allow-Origin", "*");
    }
}
