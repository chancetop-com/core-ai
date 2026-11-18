package ai.core.cli.appserver;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.CustomEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.sandbox.SandboxConstants;
import ai.core.skill.SkillCapability;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ToolExposure;
import ai.core.tool.tools.ToolActivationTool;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Loopback HTTP surface for one engine's local sessions. The sandbox runtime does the same job for
 * server sessions; this is the desktop equivalent, bound by a per-session secret in the URL so a
 * script outside the session cannot borrow its identity.
 *
 * @author core-ai
 */
public final class LocalSdkBroker implements AutoCloseable {
    private static final String CONTRACT_VERSION = "1.0";
    private static final String PREFIX = "/hub/";
    private static final String EMPTY_ARGUMENTS = "{}";
    private static final String FUNCTION_TYPE = "function";

    private final Function<String, EngineSession> sessions;
    private final HttpServer server;
    private final Map<String, String> tokenToSession = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToToken = new ConcurrentHashMap<>();

    public LocalSdkBroker(Function<String, EngineSession> sessions) throws IOException {
        this.sessions = sessions;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext(PREFIX, this::handle);
        this.server.start();
    }

    public String bind(String sessionId) {
        var existing = sessionToToken.get(sessionId);
        if (existing != null) return url(existing);
        var token = UUID.randomUUID().toString().replace("-", "");
        sessionToToken.put(sessionId, token);
        tokenToSession.put(token, sessionId);
        return url(token);
    }

    public void unbind(String sessionId) {
        var token = sessionToToken.remove(sessionId);
        if (token != null) tokenToSession.remove(token);
    }

    private String url(String token) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PREFIX + token;
    }

    @Override
    public void close() {
        server.stop(0);
        tokenToSession.clear();
        sessionToToken.clear();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            var path = exchange.getRequestURI().getPath();
            if (!path.startsWith(PREFIX)) {
                write(exchange, 404, error("not found"));
                return;
            }
            var rest = path.substring(PREFIX.length());
            var separator = rest.indexOf('/');
            var token = separator < 0 ? rest : rest.substring(0, separator);
            var route = separator < 0 ? "/" : rest.substring(separator);
            var sessionId = tokenToSession.get(token);
            var engineSession = sessionId == null ? null : sessions.apply(sessionId);
            if (engineSession == null) {
                write(exchange, 401, error("not_bound"));
                return;
            }
            route(exchange, route, engineSession);
        } catch (Exception e) {
            try {
                write(exchange, 500, error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } catch (IOException ignored) {
                // the client is already gone
            }
        }
    }

    private void route(HttpExchange exchange, String route, EngineSession session) throws IOException {
        var method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/me".equals(route)) {
            write(exchange, 200, me(session));
            return;
        }
        if ("GET".equals(method) && "/catalog".equals(route)) {
            write(exchange, 200, catalog(session));
            return;
        }
        if ("GET".equals(method) && "/tools".equals(route)) {
            write(exchange, 200, tools(session, exchange.getRequestURI().getQuery()));
            return;
        }
        if ("GET".equals(method) && route.startsWith("/tools/")) {
            var name = decode(route.substring("/tools/".length()));
            var tool = find(session, name);
            if (tool == null) {
                write(exchange, 404, error("tool not found: " + name));
                return;
            }
            write(exchange, 200, detail(session, tool));
            return;
        }
        if ("POST".equals(method) && route.startsWith("/tools/") && route.endsWith("/call")) {
            var name = decode(route.substring("/tools/".length(), route.length() - "/call".length()));
            call(exchange, session, name);
            return;
        }
        if ("GET".equals(method) && route.startsWith("/tasks/")) {
            write(exchange, 404, error("task not found"));
            return;
        }
        write(exchange, 404, error("not found"));
    }

    private ObjectNode me(EngineSession session) {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("session_id", session.id());
        node.put("agent_name", session.agent().getName());
        node.put("sandbox_state", "local");
        node.put("tool_count", visibleTools(session).size());
        node.put("contract_version", CONTRACT_VERSION);
        return node;
    }

    private ObjectNode catalog(EngineSession session) {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("session_id", session.id());
        node.put("agent_name", session.agent().getName());
        node.put("sandbox_state", "local");
        var tools = node.putArray("tools");
        for (var tool : visibleTools(session)) {
            tools.add(detail(session, tool));
        }
        node.putArray("groups");
        node.putArray("datasets");
        return node;
    }

    private ObjectNode tools(EngineSession session, String query) throws IOException {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var tools = node.putArray("tools");
        var filter = query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8);
        for (var tool : visibleTools(session)) {
            if (!filter.isBlank() && !matches(session, tool, filter)) continue;
            tools.add(summary(session, tool));
        }
        return node;
    }

    private boolean matches(EngineSession session, ToolCall tool, String query) {
        var capability = capability(session, tool);
        var text = (tool.getName() + " " + (capability == null ? "" : capability.path()) + " "
                + (tool.getDescription() == null ? "" : tool.getDescription())).toLowerCase(Locale.ROOT);
        return text.contains(query.toLowerCase(Locale.ROOT));
    }

    private ObjectNode detail(EngineSession session, ToolCall tool) {
        var node = summary(session, tool);
        node.put("input_schema", JsonUtil.toJson(tool.toJsonSchema()));
        node.put("timeout_seconds", 120);
        return node;
    }

    private ObjectNode summary(EngineSession session, ToolCall tool) {
        var capability = capability(session, tool);
        var path = capability == null ? tool.getName() : capability.path();
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("name", scriptName(path));
        node.put("kind", capability == null ? kind(tool) : capability.kind());
        node.put("group", capability == null ? nullToEmpty(tool.getNamespace()) : nullToEmpty(capability.group()));
        node.put("path", path);
        node.put("ref_id", capability == null ? tool.getName() : capability.refId());
        node.put("description", tool.getDescription());
        node.put("exposure", tool.getExposure().name().toLowerCase(Locale.ROOT));
        return node;
    }

    private SkillCapability capability(EngineSession session, ToolCall tool) {
        for (var capability : session.agent().getExecutionContext().getSkillCapabilities()) {
            if (capability != null && capability.tool() != null && capability.tool().getName().equals(tool.getName())) {
                return capability;
            }
        }
        return null;
    }

    private String scriptName(String path) {
        return path == null ? "" : path.replaceAll("[^A-Za-z0-9]", "_");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String kind(ToolCall tool) {
        if (tool.isSubAgent()) return "agent";
        var sourceType = tool.getSourceType();
        if ("mcp".equals(sourceType)) return "mcp";
        if ("api".equals(sourceType)) return "api";
        if ("llm-call".equals(sourceType)) return "llm_call";
        return "builtin";
    }

    private List<ToolCall> visibleTools(EngineSession session) {
        var context = session.agent().getExecutionContext();
        if (context.isSkillCapabilityScopeActive()) {
            var capabilities = new ArrayList<ToolCall>();
            for (var capability : context.getSkillCapabilities()) {
                if (capability == null || capability.tool() == null) continue;
                if (capabilities.stream().noneMatch(tool -> tool.getName().equals(capability.tool().getName()))) {
                    capabilities.add(capability.tool());
                }
            }
            return capabilities;
        }
        var result = new ArrayList<ToolCall>();
        for (var tool : session.agent().getToolCalls()) {
            if (SandboxConstants.INTERCEPTED_TOOLS.contains(tool.getName())) continue;
            if (ToolActivationTool.TOOL_NAME.equals(tool.getName())) continue;
            if (tool.getExposure() == ToolExposure.HIDDEN) continue;
            result.add(tool);
        }
        return result;
    }

    private ToolCall find(EngineSession session, String name) {
        for (var tool : visibleTools(session)) {
            if (tool.getName().equals(name)) return tool;
            var capability = capability(session, tool);
            if (capability != null && (capability.path().equals(name)
                    || capability.refId().equals(name)
                    || scriptName(capability.path()).equals(name))) {
                return tool;
            }
        }
        return null;
    }

    private void call(HttpExchange exchange, EngineSession session, String name) throws IOException {
        var tool = find(session, name);
        if (tool == null) {
            write(exchange, 404, error("tool not found: " + name));
            return;
        }
        var node = body(exchange);
        var arguments = node.path("arguments").asText(EMPTY_ARGUMENTS);
        var context = session.agent().getExecutionContext();
        var callId = UUID.randomUUID().toString();
        var result = session.agent().executeToolOutsideTurn(tool,
                FunctionCall.of(callId, FUNCTION_TYPE, tool.getName(), arguments), context);
        var response = result(result, callId);
        write(exchange, 200, response);
        emit(session, tool, response);
    }

    private ObjectNode result(ToolCallResult result, String callId) {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("call_id", callId);
        node.put("duration_ms", result.getDurationMs());
        if (result.isPending() || result.isWaitingForInput() || result.isAsyncLaunched()) {
            node.put("status", "pending");
            node.put("task_id", result.getTaskId());
        } else if (result.isFailed()) {
            node.put("status", "failed");
            node.put("is_error", true);
            node.put("error_message", result.getResult());
        } else {
            node.put("status", "completed");
            node.put("is_error", false);
        }
        node.put("text", result.getResult() == null ? "" : result.getResult());
        return node;
    }

    private void emit(EngineSession session, ToolCall tool, ObjectNode response) {
        try {
            var card = JsonUtil.OBJECT_MAPPER.createObjectNode();
            card.put("schema_version", 1);
            card.put("title", "Skill SDK · " + tool.getName());
            var block = card.putArray("blocks").addObject();
            block.put("type", "key_values");
            var items = block.putArray("items");
            item(items, "Capability", kind(tool) + ": " + tool.getName());
            item(items, "Status", response.path("status").asText());
            item(items, "Duration", response.path("duration_ms").asLong() + " ms");
            var data = JsonUtil.OBJECT_MAPPER.createObjectNode();
            data.put("call_id", response.path("call_id").asText());
            data.put("capability", tool.getName());
            data.put("kind", kind(tool));
            data.put("status", response.path("status").asText());
            data.put("duration_ms", response.path("duration_ms").asLong());
            session.inProcess().dispatchEvent(CustomEvent.of(session.id(), "skill_sdk_call",
                    JsonUtil.toJson(data), JsonUtil.toJson(card), response.path("call_id").asText()));
        } catch (RuntimeException ignored) {
            // the call already succeeded; a display event must not turn it into a failure
        }
    }

    private void item(com.fasterxml.jackson.databind.node.ArrayNode items, String label, String value) {
        var item = items.addObject();
        item.put("label", label);
        item.put("value", value);
    }

    private ObjectNode body(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            var bytes = input.readAllBytes();
            if (bytes.length == 0) return JsonUtil.OBJECT_MAPPER.createObjectNode();
            JsonNode node = JsonUtil.OBJECT_MAPPER.readTree(bytes);
            return node instanceof ObjectNode object ? object : JsonUtil.OBJECT_MAPPER.createObjectNode();
        }
    }

    private ObjectNode error(String message) {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("error", message == null ? "request failed" : message);
        return node;
    }

    private void write(HttpExchange exchange, int status, ObjectNode body) throws IOException {
        var bytes = JsonUtil.OBJECT_MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
