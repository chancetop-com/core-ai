package ai.core.server.hub;

import ai.core.api.server.hub.HubCallWebService;
import core.framework.module.Module;

/**
 * Read side of the hub call audit records ({@code hub_calls}): the observability "Hub Calls" list.
 * Loaded after the hub modules that write the records so the collection, its indexes and the read
 * endpoint stay in one place.
 *
 * @author stephen
 */
public class HubCallModule extends Module {
    @Override
    protected void initialize() {
        bind(HubCallQueryService.class);
        api().service(HubCallWebService.class, bind(HubCallWebServiceImpl.class));
    }
}
