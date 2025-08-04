package ai.core.example;

import ai.core.example.api.ExampleWebService;
import ai.core.example.api.ExampleWebServiceImpl;
import ai.core.example.service.CodingMcpService;
import ai.core.example.service.ExampleService;
import ai.core.example.service.UserInfoService;
import ai.core.example.service.WeatherService;
import ai.core.mcp.server.McpServerService;
import core.framework.module.Module;
import core.framework.util.ClasspathResources;

/**
 * @author stephen
 */
public class ExampleModule extends Module {
    @Override
    protected void initialize() {
        bind(WeatherService.class);
        bind(UserInfoService.class);
        bind(ExampleService.class);
        api().service(ExampleWebService.class, bind(ExampleWebServiceImpl.class));

        // mcp server
        bind(new CodingMcpService(ClasspathResources.text("core-ng-wiki.md")));
        onStartup(() -> {
            var service = (McpServerService) context.beanFactory.bean(McpServerService.class, null);
            var apiService = (CodingMcpService) context.beanFactory.bean(CodingMcpService.class, null);
            service.setToolLoader(apiService);
        });
    }
}
