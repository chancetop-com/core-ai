package ai.core;

import ai.core.api.mcp.JsonRpcResponse;
import ai.core.api.mcp.kafka.McpKafkaTopics;
import ai.core.api.mcp.kafka.McpToolCallEvent;
import ai.core.mcp.server.McpServerChannelService;
import ai.core.mcp.server.McpServerService;
import ai.core.mcp.server.McpServerSseListener;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class McpServerModule extends Module {
    @Override
    protected void initialize() {
        kafka().publish(McpKafkaTopics.MCP_TOOL_CALL_EVENT, McpToolCallEvent.class);
        bind(McpServerChannelService.class);
        bind(McpServerService.class);
        sse().listen(HTTPMethod.POST, "/mcp", JsonRpcResponse.class, bind(McpServerSseListener.class));
    }
}
