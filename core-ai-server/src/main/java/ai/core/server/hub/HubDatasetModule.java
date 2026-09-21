package ai.core.server.hub;

import ai.core.api.server.hub.HubDatasetWebService;
import core.framework.module.Module;

/**
 * Session-anchored dataset access over HTTP, for scripts running outside a sandbox (CLI / SDK local mode).
 * The sandbox side is served by the session's own tools instead; both ends share the same registry and write
 * rules, so a script sees exactly what the session agent sees.
 *
 * @author stephen
 */
public class HubDatasetModule extends Module {
    @Override
    protected void initialize() {
        bind(HubDatasetService.class);
        api().service(HubDatasetWebService.class, bind(HubDatasetWebServiceImpl.class));
    }
}
