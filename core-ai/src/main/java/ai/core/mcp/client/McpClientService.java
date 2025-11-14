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
 * MCP Client Service for interacting with Model Context Protocol servers.
 * 
 * This service uses McpHTTPClientAdvanced to ensure full MCP compliance,
 * including proper Accept header handling.
 * 
 * @author stephen
 */
public class McpClientService {
    private final McpClientServerConfig config;
    private final HTTPClient client;

    public McpClientService(McpClientServerConfig config) {
        this(config, true);
    }

    /**
     * Create McpClientService with option to use advanced MCP-compliant client.
     * 
     * @param config MCP server configuration
     * @param useAdvancedClient if true, uses McpHTTPClientAdvanced for full MCP compliance;
     *                          if false, uses standard HTTPClient (Accept header limitation)
     */
    public McpClientService(McpClientServerConfig config, boolean useAdvancedClient) {
        this.config = config;
        if (useAdvancedClient) {
            // Use advanced client with full MCP compliance (preserves Accept header)
            this.client = McpHTTPClientAdvanced.create();
        } else {
            // Use standard client (Accept header will be overwritten to "text/event-stream" only)
            this.client = HTTPClient.builder()
                .connectTimeout(Duration.ofMillis(500))
                .timeout(Duration.ofSeconds(10))
                .build();
        }
    }

    /**
     * Add custom headers required by MCP Streamable HTTP transport.
     * Reference: <a href="https://modelcontextprotocol.io/specification/2025-06-18/basic/transports">...</a>
     * <p>
     * Note: When using standard HTTPClient, the Accept header will be overwritten.
     *       Use McpHTTPClientAdvanced (via useAdvancedClient=true constructor) for full compliance.
     */
    private void addCustomHeaders(HTTPRequest request) {
        // First, apply custom headers from config (if any)
        if (config.headers() != null && !config.headers().isEmpty()) {
            config.headers().forEach(request.headers::put);
        }
        
        // Then, apply MCP required headers (these will override config headers if conflicting)
        // MCP Protocol Version Header (required by MCP spec)
        request.headers.put("MCP-Protocol-Version", "2025-06-18");
        
        // MCP-compliant Accept header (will be overwritten by standard HTTPClient.sse())
        request.headers.put("Accept", "application/json, text/event-stream");
        
        // Content-Type for JSON-RPC requests
        request.headers.put("Content-Type", "application/json");
    }

    public List<Tool> listTools(List<String> namespaces) {
        var request = new HTTPRequest(HTTPMethod.POST, config.url());
        addCustomHeaders(request);
        Object params = null;
        if (namespaces != null && !namespaces.isEmpty()) {
            params = ListToolRequest.of(namespaces);
        }
        var req = JsonRpcRequest.of(Constants.JSONRPC_VERSION, MethodEnum.METHOD_TOOLS_LIST, UUID.randomUUID().toString(), params);
        request.body = JSON.toJSON(req).getBytes();
        try (var response = client.sse(request)) {
            var iterator = response.iterator();
            if (iterator.hasNext()) {
                var event = iterator.next();
                var rsp = JsonUtil.fromJson(JsonRpcResponse.class, event.data());
                var rst = JsonUtil.fromJson(ListToolsResult.class, rsp.result);
                return rst.tools;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return List.of();
    }

    public List<Tool> listTools() {
        return listTools(null);
    }

    public String callTool(String name, String text) {
        var request = new HTTPRequest(HTTPMethod.POST, config.url());
        addCustomHeaders(request);
        var params = CallToolRequest.of(name, text);
        var req = JsonRpcRequest.of(Constants.JSONRPC_VERSION, MethodEnum.METHOD_TOOLS_CALL, UUID.randomUUID().toString(), params);
        request.body = JsonUtil.toJson(req).getBytes();
        try (var response = client.sse(request)) {
            var iterator = response.iterator();
            if (iterator.hasNext()) {
                var event = iterator.next();
                var rsp = JsonUtil.fromJson(JsonRpcResponse.class, event.data());
                if (rsp.result == null && rsp.error == null) {
                    return "Call tool with no result & no error";
                }
                if (rsp.result == null) {
                    return rsp.error.message;
                }
                var rst = JsonUtil.fromJson(CallToolResult.class, rsp.result);
                return rst.content.getFirst().text;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "Call tool with no result & no error";
    }
}
