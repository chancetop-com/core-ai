package ai.core;

import ai.core.api.mcp.JsonRpcResponse;
import ai.core.mcp.server.McpChannelService;
import ai.core.mcp.server.McpService;
import ai.core.mcp.server.McpSseListener;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class McpServerModule extends Module {
    @Override
    protected void initialize() {
        bind(McpChannelService.class);
        bind(McpService.class);
        sse().listen(HTTPMethod.PUT, "/mcp", JsonRpcResponse.class, bind(McpSseListener.class));
    }
}
