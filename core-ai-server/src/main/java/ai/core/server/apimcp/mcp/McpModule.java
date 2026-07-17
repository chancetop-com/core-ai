package ai.core.server.apimcp.mcp;

import ai.core.mcp.server.McpServerService;
import ai.core.mcp.server.apiserver.ApiMcpToolLoader;
import ai.core.api.server.McpWebService;
import ai.core.server.apimcp.mcp.api.McpWebServiceImpl;
import ai.core.server.apimcp.mcp.service.ApiLoaderService;
import ai.core.server.apimcp.mcp.service.McpService;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class McpModule extends Module {
    @Override
    protected void initialize() {
        bind(McpService.class);
        var apiLoaderService = bind(ApiLoaderService.class);
        api().service(McpWebService.class, bind(McpWebServiceImpl.class));
        var mcpServerService = bean(McpServerService.class);
        onStartup(() -> mcpServerService.setToolLoader(new ApiMcpToolLoader(apiLoaderService)));
    }
}
