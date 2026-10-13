package ai.core.api.server.runtime;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface RuntimeWebService {
    @GET
    @Path("/api/admin/runtime/limits")
    RuntimeLimitsView limits();

    @PUT
    @Path("/api/admin/runtime/limits")
    RuntimeLimitsView updateLimits(RuntimeLimitsRequest request);

    @GET
    @Path("/api/admin/runtime/stats")
    RuntimeStatsView stats();
}
