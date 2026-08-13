package ai.core.api.server;

import ai.core.api.a2a.A2ACapabilities;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface CapabilitiesWebService {
    @GET
    @Path("/api/capabilities")
    A2ACapabilities get();
}
