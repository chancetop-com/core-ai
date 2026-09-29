package ai.core.server.cataloghub;

import ai.core.api.server.HubCatalogWebService;
import core.framework.module.Module;

/**
 * The one-shot hub catalog: composes the MCP, API-tool and agent catalogs into a single response,
 * so it loads after all three modules have bound their catalog services.
 *
 * @author stephen
 */
public class HubCatalogModule extends Module {
    @Override
    protected void initialize() {
        bind(HubCatalogService.class);
        api().service(HubCatalogWebService.class, bind(HubCatalogWebServiceImpl.class));
    }
}
