package ai.core.api.server.runtime;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

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

    /** Live snapshot of the answering pod (ad-hoc checks); the dashboard reads {@link #pods()} instead. */
    @GET
    @Path("/api/admin/runtime/stats")
    RuntimeStatsView stats();

    /** Newest sample of every replica that reported within the lookback window. */
    @GET
    @Path("/api/admin/runtime/pods")
    RuntimePodsView pods();

    @GET
    @Path("/api/admin/runtime/series/:hours/:bucketMinutes")
    RuntimeSeriesView series(@PathParam("hours") int hours, @PathParam("bucketMinutes") int bucketMinutes);
}
