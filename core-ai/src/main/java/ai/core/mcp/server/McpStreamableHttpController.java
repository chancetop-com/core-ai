package ai.core.mcp.server;

import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author stephen
 */
public class McpStreamableHttpController implements Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpStreamableHttpController.class);
    private static final ContentType APPLICATION_JSON = ContentType.APPLICATION_JSON;
    private static final McpJsonMapper MCP_JSON_MAPPER = McpJsonMapper.createDefault();

    @Inject
    McpServerService serverHolder;

    @Override
    public Response execute(Request request) {
        var body = request.body().orElse(new byte[0]);
        if (body.length == 0) {
            return Response.text("Bad Request: empty body").status(HTTPStatus.BAD_REQUEST);
        }

        var jsonBody = new String(body);
        ActionLogContext.put("mcp_request", jsonBody);

        try {
            var map = JsonUtil.toMap(jsonBody);
            if (!map.containsKey("method")) {
                return Response.text("Bad Request: invalid JSON-RPC message").status(HTTPStatus.BAD_REQUEST);
            }
            if (map.containsKey("id")) {
                var mcpRequest = JsonUtil.fromJson(McpSchema.JSONRPCRequest.class, jsonBody);
                var response = this.serverHolder.getTransportProvider().handleRequest(mcpRequest);
                var responseJson = toMcpJson(response);
                ActionLogContext.put("mcp_response", responseJson);
                return Response.bytes(responseJson.getBytes()).contentType(APPLICATION_JSON).header("Access-Control-Allow-Origin", "*");
            } else {
                var notification = JsonUtil.fromJson(McpSchema.JSONRPCNotification.class, jsonBody);
                this.serverHolder.getTransportProvider().handleNotification(notification);
                return Response.empty().status(HTTPStatus.ACCEPTED).header("Access-Control-Allow-Origin", "*");
            }
        } catch (Exception e) {
            LOGGER.error("Error handling MCP request", e);
            var errorResponse = this.createErrorResponse(e.getMessage());
            return Response.bytes(errorResponse.getBytes()).contentType(APPLICATION_JSON).status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }

    }

    private String createErrorResponse(String errorMessage) {
        return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\""
                + (errorMessage != null ? errorMessage.replace("\"", "\\\"") : "Unknown error") + "\"},\"id\":null}";
    }

    private String toMcpJson(Object obj) {
        try {
            return MCP_JSON_MAPPER.writeValueAsString(obj);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize MCP response", e);
        }
    }
}
