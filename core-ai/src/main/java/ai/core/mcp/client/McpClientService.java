package ai.core.mcp.client;

import ai.core.api.mcp.Constants;
import ai.core.api.mcp.JsonRpcRequest;
import ai.core.api.mcp.JsonRpcResponse;
import ai.core.api.mcp.MethodEnum;
import ai.core.api.mcp.schema.tool.CallToolRequest;
import ai.core.api.mcp.schema.tool.CallToolResult;
import ai.core.api.mcp.schema.tool.ListToolsResult;
import ai.core.api.mcp.schema.tool.Tool;
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
    private final McpClientServerConfig config;
    private final HTTPClient client = HTTPClient.builder().connectTimeout(Duration.ofMillis(500)).timeout(Duration.ofSeconds(10)).build();

    public McpClientService(McpClientServerConfig config) {
        this.config = config;
    }

    public List<Tool> listTools() {
        var request = new HTTPRequest(HTTPMethod.POST, config.url());
        var req = JsonRpcRequest.of(Constants.JSONRPC_VERSION, MethodEnum.METHOD_TOOLS_LIST, UUID.randomUUID().toString(), "");
        request.body = JSON.toJSON(req).getBytes();
        try (var response = client.sse(request)) {
            for (var event : response) {
                var rsp = JSON.fromJSON(JsonRpcResponse.class, event.data());
                return JSON.fromJSON(ListToolsResult.class, rsp.result).tools;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return List.of();
    }

    public String callTool(String name, String text) {
        var request = new HTTPRequest(HTTPMethod.POST, config.url());
        var params = JSON.toJSON(CallToolRequest.of(name, text));
        var req = JsonRpcRequest.of(Constants.JSONRPC_VERSION, MethodEnum.METHOD_TOOLS_CALL, UUID.randomUUID().toString(), params);
        request.body = JSON.toJSON(req).getBytes();
        try (var response = client.sse(request)) {
            for (var event : response) {
                var rsp = JSON.fromJSON(JsonRpcResponse.class, event.data());
                if (rsp.result == null && rsp.error == null) {
                    return "Call tool with no result & no error";
                }
                if (rsp.result == null) {
                    return rsp.error.message;
                }
                return JSON.fromJSON(CallToolResult.class, rsp.result).content.getFirst().content;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "Call tool with no result & no error";
    }
}
