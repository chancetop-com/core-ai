package ai.core.mcp.client;

import core.framework.json.JSON;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.client.McpAsyncClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class MCPClientService {
    private final MCPServerConfig config;
    private McpAsyncClient client;

    public MCPClientService(MCPServerConfig config) {
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    public void callTool(String name, String params, MCPToolCallMessageHandler mcpToolCallMessageHandler) {
        if (client == null) {
            client = connect(config.url());
        }

        var argsMap = JSON.fromJSON(Map.class, params);
        var request = new McpSchema.CallToolRequest(name, argsMap);
        MCPClient.INSTANCE.callTool(client, request).subscribe(callToolResult -> {
            if (callToolResult.isError()) throw new RuntimeException(toTextContentString(callToolResult.content()));
            mcpToolCallMessageHandler.resultHandler(toTextContentString(callToolResult.content()));
        });
    }

    private String toTextContentString(List<McpSchema.Content> result) {
        return result.stream().map(v -> {
            if ("text".equals(v.type())) {
                return ((TextContent) v).text();
            }
            return v.toString();
        }).collect(Collectors.joining("\n"));
    }

    public List<Tool> listTools() {
        if (client == null) {
            client = connect(config.url());
        }
        return MCPClient.INSTANCE.listTools(client);
    }

    public List<Prompt> listPrompts() {
        if (client == null) {
            client = connect(config.url());
        }
        return MCPClient.INSTANCE.listPrompts(client);
    }

    public List<Resource> listResources() {
        if (client == null) {
            client = connect(config.url());
        }
        return MCPClient.INSTANCE.listResources(client);
    }

    public McpAsyncClient connect(String url) {
        return MCPClient.INSTANCE.connect(url);
    }

    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
