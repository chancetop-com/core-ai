package ai.core.api.server.workflow;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author Xander
 */
public interface WorkflowWebService {
    @POST
    @Path("/api/workflows")
    WorkflowView create(CreateWorkflowRequest request);

    @POST
    @Path("/api/workflows/:id/publish")
    WorkflowView publish(@PathParam("id") String id);

    @POST
    @Path("/api/workflows/:id/runs")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    CreateRunResponse createRun(@PathParam("id") String id, CreateRunRequest request);

    @GET
    @Path("/api/workflow-runs/:runId")
    WorkflowRunView getRun(@PathParam("runId") String runId);
}
