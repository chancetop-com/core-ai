package ai.core.server.hub;

import ai.core.api.server.hub.HubDatasetWebService;
import core.framework.module.Module;

/**
 * Dataset access over HTTP for scripts running outside a sandbox (CLI / SDK local mode): session-anchored access
 * through {@link HubDatasetService} and session-less GENERAL data through {@link HubDatasetCatalogService}.
 * The sandbox side is served by the session's own tools instead; both ends share the same write rules and payloads.
 *
 * @author stephen
 */
public class HubDatasetModule extends Module {
    @Override
    protected void initialize() {
        bind(HubDatasetService.class);
        bind(HubDatasetCatalogService.class);
        api().service(HubDatasetWebService.class, bind(HubDatasetWebServiceImpl.class));
    }
}
