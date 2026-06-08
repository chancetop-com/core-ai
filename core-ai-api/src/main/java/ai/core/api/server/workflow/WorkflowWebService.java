package ai.core.api.server.workflow;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author Xander
 */
public interface WorkflowWebService {
    @GET
    @Path("/api/workflows")
    ListWorkflowsResponse list();

    @POST
    @Path("/api/workflows")
    WorkflowView create(CreateWorkflowRequest request);

    @GET
    @Path("/api/workflows/:id")
    WorkflowView get(@PathParam("id") String id);

    @PUT
    @Path("/api/workflows/:id")
    WorkflowView update(@PathParam("id") String id, UpdateWorkflowRequest request);

    @DELETE
    @Path("/api/workflows/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/workflows/:id/validate")
    ValidateWorkflowResponse validate(@PathParam("id") String id);

    @POST
    @Path("/api/workflows/:id/publish")
    WorkflowView publish(@PathParam("id") String id);

    @POST
    @Path("/api/workflows/:id/runs")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    CreateRunResponse createRun(@PathParam("id") String id, CreateRunRequest request);

    // Run the current draft without publishing (editor preview).
    @POST
    @Path("/api/workflows/:id/preview-runs")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    CreateRunResponse createPreviewRun(@PathParam("id") String id, CreateRunRequest request);

    @GET
    @Path("/api/workflows/:id/runs")
    ListWorkflowRunsResponse listRuns(@PathParam("id") String id);

    @GET
    @Path("/api/workflow-runs/:runId")
    WorkflowRunView getRun(@PathParam("runId") String runId);

    @GET
    @Path("/api/workflow-runs/:runId/nodes")
    ListNodeRunsResponse listNodeRuns(@PathParam("runId") String runId);
}
