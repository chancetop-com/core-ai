package ai.core.tool.mcp;

import ai.core.mcp.client.McpClientManager;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;

/**
 * @author stephen
 */
public class McpToolCall extends ToolCall {

    public static Builder builder() {
        return new Builder();
    }

    McpClientManager mcpClientManager;
    String serverName;

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var result = mcpClientManager.safeCallTool(serverName, this.getName(), text);
            return result.withDuration(System.currentTimeMillis() - startTime).withDirectReturn(isDirectReturn());
        } catch (Exception e) {
            return ToolCallResult.failed("MCP call failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime).withDirectReturn(isDirectReturn());
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ToolCall> {
        private McpClientManager mcpClientManager;
        private String serverName;

        public Builder mcpClientManager(McpClientManager mcpClientManager) {
            this.mcpClientManager = mcpClientManager;
            return this;
        }

        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        public McpToolCall build() {
            var toolCall = new McpToolCall();
            if (mcpClientManager == null) {
                throw new RuntimeException("McpClientManager is required");
            }
            if (serverName == null || serverName.isBlank()) {
                throw new RuntimeException("serverName is required");
            }
            build(toolCall);
            toolCall.mcpClientManager = mcpClientManager;
            toolCall.serverName = serverName;
            return toolCall;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
