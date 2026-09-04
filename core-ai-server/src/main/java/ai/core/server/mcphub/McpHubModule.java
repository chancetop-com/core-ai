package ai.core.server.mcphub;

import ai.core.api.server.McpHubWebService;
import ai.core.server.tool.ToolRegistryService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * MCP Hub surface: read-only catalog, keyword search and tool execution over registered
 * MCP servers, without exposing any server config/credentials. Loaded right after
 * {@code ToolRegistryModule} so its beans can be injected by later modules.
 *
 * @author stephen
 */
public class McpHubModule extends Module {
    @Override
    protected void initialize() {
        var catalog = bind(McpToolCatalogService.class);
        var toolRegistryService = bean(ToolRegistryService.class);
        // Wire after ToolRegistryService.initialize() ran (its onStartup precedes ours) so the
        // registry operation service exists when server changes invalidate the hub catalog.
        onStartup(() -> toolRegistryService.setMcpCatalogInvalidator(catalog::invalidate));

        bind(McpHubAccessPolicy.class);
        bind(McpHubService.class);

        api().service(McpHubWebService.class, bind(McpHubWebServiceImpl.class));
        schedule().fixedRate("mcp-hub-catalog-sync", bind(McpHubCatalogSyncJob.class), Duration.ofSeconds(30));
    }
}
