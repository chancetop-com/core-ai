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
        bind(ApiLoaderService.class);
        api().service(McpWebService.class, bind(McpWebServiceImpl.class));
        onStartup(() -> {
            var service = (McpServerService) context.beanFactory.bean(McpServerService.class, null);
            var apiService = (ApiLoaderService) context.beanFactory.bean(ApiLoaderService.class, null);
            var loader = new ApiMcpToolLoader(apiService);
            service.setToolLoader(loader);
        });
    }
}
