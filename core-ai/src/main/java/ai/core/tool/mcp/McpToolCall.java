package ai.core.tool.mcp;

import ai.core.mcp.client.McpClientService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;

/**
 * @author stephen
 */
public class McpToolCall extends ToolCall {

    public static Builder builder() {
        return new Builder();
    }

    McpClientService mcpClientService;

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var result = mcpClientService.callTool(this.getName(), text);
            return ToolCallResult.completed(result)
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("MCP call failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ToolCall> {
        private McpClientService mcpClientService;

        public Builder mcpClientService(McpClientService mcpClientService) {
            this.mcpClientService = mcpClientService;
            return this;
        }

        public McpToolCall build() {
            var toolCall = new McpToolCall();
            if (mcpClientService == null) {
                throw new RuntimeException("MCPClientService is required");
            }
            build(toolCall);
            toolCall.mcpClientService = mcpClientService;
            return toolCall;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
