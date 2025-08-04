package ai.core;

import ai.core.api.mcp.JsonRpcResponse;
import ai.core.mcp.MCPServerSentEventConfig;
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
        bind(McpServerChannelService.class);
        bind(McpServerService.class);
        var sseConfig = config(MCPServerSentEventConfig.class, "mcpServerSse");
//        http().route(HTTPMethod.GET, "/mcp", new McpSseController());
        sseConfig.listen(HTTPMethod.POST, "/mcp", JsonRpcResponse.class, bind(McpServerSseListener.class));
    }
}
