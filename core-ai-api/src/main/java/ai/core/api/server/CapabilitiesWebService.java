package ai.core.api.server;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface CapabilitiesWebService {
    @GET
    @Path("/api/capabilities")
    ServerCapabilities get();
}
