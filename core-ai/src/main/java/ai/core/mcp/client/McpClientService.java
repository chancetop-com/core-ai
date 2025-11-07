package ai.core.mcp.client;

import ai.core.api.mcp.Constants;
import ai.core.api.mcp.JsonRpcRequest;
import ai.core.api.mcp.JsonRpcResponse;
import ai.core.api.mcp.MethodEnum;
import ai.core.api.mcp.schema.tool.CallToolRequest;
import ai.core.api.mcp.schema.tool.CallToolResult;
import ai.core.api.mcp.schema.tool.ListToolRequest;
import ai.core.api.mcp.schema.tool.ListToolsResult;
import ai.core.api.mcp.schema.tool.Tool;
import ai.core.utils.JsonUtil;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class McpClientService {
    private static final String MCP_PROTOCOL_VERSION = "2025-06-18";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String ACCEPT_JSON_SSE = "application/json, text/event-stream";
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";

    private final McpClientServerConfig config;
    private final HTTPClient client = HTTPClient.builder().connectTimeout(Duration.ofMillis(500)).timeout(Duration.ofSeconds(10)).build();
    private String sessionId;

    public McpClientService(McpClientServerConfig config) {
        this.config = config;
    }

    /**
     * Initialize session with MCP server. Must be called before other operations.
     * For Streamable HTTP transport, this establishes an SSE connection with a client-generated session ID.
     * @return session ID
     */
    public String initialize() {
        // Generate session ID on client side (as per Streamable HTTP spec)
        this.sessionId = UUID.randomUUID().toString();

        // Establish SSE connection with GET request
        var request = new HTTPRequest(HTTPMethod.GET, config.url());
        setHeaders(request);  // This will include the generated session ID

        try {
            // Just verify connection works - don't wait for full response
            // The actual SSE stream will be used by subsequent requests
            var response = client.execute(request);

            // Verify we got a successful SSE response
            String contentType = response.headers.get("Content-Type");
            if (response.statusCode != 200 || contentType == null || !contentType.startsWith("text/event-stream")) {
                throw new RuntimeException("Failed to establish SSE connection. Status: " + response.statusCode + ", Content-Type: " + contentType);
            }

            return this.sessionId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize MCP session", e);
        }
    }

    private void setHeaders(HTTPRequest request) {
        // Set default MCP required headers
        request.headers.put("Content-Type", CONTENT_TYPE_JSON);
        request.headers.put("Accept", ACCEPT_JSON_SSE);
        request.headers.put("MCP-Protocol-Version", MCP_PROTOCOL_VERSION);

        // Add session ID if available
        if (sessionId != null) {
            request.headers.put(HEADER_SESSION_ID, sessionId);
        }

        // Apply user-configured headers (can override defaults)
        if (config.headers() != null) {
            request.headers.putAll(config.headers());
        }
    }

    public List<Tool> listTools(List<String> namespaces) {
        var request = new HTTPRequest(HTTPMethod.POST, config.url());
        setHeaders(request);

        Object params = null;
        if (namespaces != null && !namespaces.isEmpty()) {
            params = ListToolRequest.of(namespaces);
        }
        var req = JsonRpcRequest.of(Constants.JSONRPC_VERSION, MethodEnum.METHOD_TOOLS_LIST, UUID.randomUUID().toString(), params);
        request.body = JSON.toJSON(req).getBytes();

        // Use execute() instead of sse() to preserve Accept header as per MCP spec
        try {
            var response = client.execute(request);
            String contentType = response.headers.get("Content-Type");

            if (contentType != null && contentType.startsWith("text/event-stream")) {
                return parseListToolsFromSSE(response.text());
            } else if (contentType != null && contentType.contains("application/json")) {
                return parseListToolsFromJSON(response.text());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return List.of();
    }

    public List<Tool> listTools() {
        return listTools(null);
    }

    private List<Tool> parseListToolsFromSSE(String body) {
        String[] lines = body.split("\n");
        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String jsonData = line.substring(6).trim();
                var rsp = JsonUtil.fromJson(JsonRpcResponse.class, jsonData);
                var rst = JsonUtil.fromJson(ListToolsResult.class, rsp.result);
                return rst.tools;
            }
        }
        return List.of();
    }

    private List<Tool> parseListToolsFromJSON(String body) {
        var rsp = JsonUtil.fromJson(JsonRpcResponse.class, body);
        var rst = JsonUtil.fromJson(ListToolsResult.class, rsp.result);
        return rst.tools;
    }

    public String callTool(String name, String text) {
        var request = new HTTPRequest(HTTPMethod.POST, config.url());
        setHeaders(request);

        var params = CallToolRequest.of(name, text);
        var req = JsonRpcRequest.of(Constants.JSONRPC_VERSION, MethodEnum.METHOD_TOOLS_CALL, UUID.randomUUID().toString(), params);
        request.body = JsonUtil.toJson(req).getBytes();

        // Use execute() instead of sse() to preserve Accept header as per MCP spec
        try {
            var response = client.execute(request);
            String contentType = response.headers.get("Content-Type");

            if (contentType != null && contentType.startsWith("text/event-stream")) {
                return parseCallToolFromSSE(response.text());
            } else if (contentType != null && contentType.contains("application/json")) {
                return parseCallToolFromJSON(response.text());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "Call tool with no result & no error";
    }

    private String parseCallToolFromSSE(String body) {
        String[] lines = body.split("\n");
        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String jsonData = line.substring(6).trim();
                return extractCallToolResult(jsonData);
            }
        }
        return "Call tool with no result & no error";
    }

    private String parseCallToolFromJSON(String body) {
        return extractCallToolResult(body);
    }

    private String extractCallToolResult(String jsonData) {
        var rsp = JsonUtil.fromJson(JsonRpcResponse.class, jsonData);
        if (rsp.result == null && rsp.error == null) {
            return "Call tool with no result & no error";
        }
        if (rsp.result == null) {
            return rsp.error.message;
        }
        var rst = JsonUtil.fromJson(CallToolResult.class, rsp.result);
        return rst.content.getFirst().text;
    }
}
