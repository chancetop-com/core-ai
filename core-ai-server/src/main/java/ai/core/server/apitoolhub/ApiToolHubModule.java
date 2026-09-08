package ai.core.server.apitoolhub;

import ai.core.api.server.ApiToolHubWebService;
import ai.core.server.apimcp.mcp.ApiToolsMcpInterceptor;
import ai.core.server.tool.ToolRegistryService;
import core.framework.module.Module;

/**
 * API-Tool Hub surface: scoring search, app/operation details and operation execution
 * over the Service API catalog, without touching the management surface
 * ({@code /api/service-api/*}, {@code /api/tools/service-api/*}). The catalog snapshot is
 * invalidated from {@link ToolRegistryService#reloadApiTools()} (ToolRegistrySyncJob detects
 * {@code service_api} changes), so no scheduled job is needed here.
 * <p>
 * Also registers the hardened {@code /api/api-tools/mcp} request interceptor — this module
 * loads after {@code WebFoundationModule}, so the interceptor chain (auth → permission →
 * cors → this) already resolved the caller identity when it runs.
 *
 * @author stephen
 */
public class ApiToolHubModule extends Module {
    @Override
    protected void initialize() {
        var catalog = bind(ApiToolCatalogService.class);
        var toolRegistryService = bean(ToolRegistryService.class);
        // Wire after ToolRegistryService.initialize() ran (its onStartup precedes ours) so the
        // catalog invalidator exists when reloadApiTools() fires on service_api changes.
        onStartup(() -> toolRegistryService.setApiToolCatalogInvalidator(catalog::invalidate));

        bind(ApiToolHubAccessPolicy.class);
        bind(ApiToolHubService.class);

        api().service(ApiToolHubWebService.class, bind(ApiToolHubWebServiceImpl.class));
        var mcpInterceptor = bind(ApiToolsMcpInterceptor.class);
        mcpInterceptor.authDisabled = "true".equals(property("sys.auth.disabled").orElse("false"));
        http().intercept(mcpInterceptor);
    }
}
