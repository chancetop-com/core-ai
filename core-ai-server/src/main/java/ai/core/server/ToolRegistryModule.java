package ai.core.server;

import ai.core.api.server.ToolRegistryWebService;
import ai.core.server.schedule.ToolRegistrySyncJob;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.ToolRegistryWebServiceImpl;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class ToolRegistryModule extends Module {
    @Override
    protected void initialize() {
        var toolRegistryService = bind(ToolRegistryService.class);

        var mcpConfig = property("mcp.servers.json").orElse(null);
        onStartup(() -> toolRegistryService.initialize(mcpConfig));

        api().service(ToolRegistryWebService.class, bind(ToolRegistryWebServiceImpl.class));
        schedule().fixedRate("tool-registry-sync", bind(ToolRegistrySyncJob.class), Duration.ofSeconds(30));
    }
}
