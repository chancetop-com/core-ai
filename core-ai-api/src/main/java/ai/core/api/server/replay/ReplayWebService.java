package ai.core.api.server.replay;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Replay debug: create experiments from trace LLM spans, run edited variants
 * against the live provider stack, and compare sample responses.
 *
 * @author stephen
 */
public interface ReplayWebService {
    @POST
    @Path("/api/replay-experiments")
    ReplayExperimentView create(CreateReplayExperimentRequest request);

    @GET
    @Path("/api/replay-experiments")
    ListReplayExperimentsResponse list(ListReplayExperimentsRequest request);

    @GET
    @Path("/api/replay-experiments/:id")
    ReplayExperimentView get(@PathParam("id") String id);

    @PUT
    @Path("/api/replay-experiments/:id")
    ReplayExperimentView update(@PathParam("id") String id, UpdateReplayExperimentRequest request);

    @DELETE
    @Path("/api/replay-experiments/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/replay-experiments/:id/runs")
    CreateReplayRunResponse createRun(@PathParam("id") String id, CreateReplayRunRequest request);

    @GET
    @Path("/api/replay-experiments/:id/runs/:runId")
    ReplayRunView getRun(@PathParam("id") String id, @PathParam("runId") String runId);

    @POST
    @Path("/api/replay-experiments/:id/runs/:runId/cancel")
    void cancelRun(@PathParam("id") String id, @PathParam("runId") String runId);
}
