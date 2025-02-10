package ai.core.tool.mcp;

import ai.core.mcp.client.MCPClientService;
import ai.core.tool.ToolCall;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * @author stephen
 */
public class MCPToolCall extends ToolCall {

    public static Builder builder() {
        return new Builder();
    }

    MCPClientService mcpClientService;

    @Override
    public String call(String text) {
        var future = new CompletableFuture<>();
        mcpClientService.callTool(this.getName(), text, future::complete);
        try {
            return (String) future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ToolCall> {
        private MCPClientService mcpClientService;

        public Builder mcpClientService(MCPClientService mcpClientService) {
            this.mcpClientService = mcpClientService;
            return this;
        }

        public MCPToolCall build() {
            var toolCall = new MCPToolCall();
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
