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
    ListWorkflowsResponse list(ListWorkflowsRequest request);

    @GET
    @Path("/api/explore/workflows")
    ExploreWorkflowsResponse explore(ExploreWorkflowsRequest request);

    @POST
    @Path("/api/workflows")
    WorkflowView create(CreateWorkflowRequest request);

    @GET
    @Path("/api/workflows/:id")
    WorkflowView get(@PathParam("id") String id);

    @GET
    @Path("/api/workflows/:id/export")
    ExportWorkflowResponse export(@PathParam("id") String id);

    @POST
    @Path("/api/workflows/import")
    ImportWorkflowResponse importWorkflow(ImportWorkflowRequest request);

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

    // Clone another user's published workflow into a fresh draft owned by the caller (discover -> copy to mine).
    @POST
    @Path("/api/workflows/:id/clone")
    CloneWorkflowResponse clone(@PathParam("id") String id);

    @POST
    @Path("/api/workflows/:id/runs")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    CreateRunResponse createRun(@PathParam("id") String id, CreateRunRequest request);

    // Synchronous run for external API callers: starts the run and blocks until it finishes (or times out),
    // returning the final run with its output — one call instead of create + poll.
    @POST
    @Path("/api/workflows/:id/run-sync")
    WorkflowRunView runSync(@PathParam("id") String id, CreateRunRequest request);

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

    // The graph snapshot the run executed (its pinned version), so history renders against what actually ran.
    @GET
    @Path("/api/workflow-runs/:runId/graph")
    WorkflowRunGraphResponse runGraph(@PathParam("runId") String runId);

    // The immutable graph for a selected published workflow version, used by WORKFLOW-node input mapping.
    @GET
    @Path("/api/workflow-versions/:versionId/graph")
    WorkflowRunGraphResponse versionGraph(@PathParam("versionId") String versionId);

    // Resume a paused run by providing a human decision/input for the HUMAN_INPUT node it is waiting on.
    @POST
    @Path("/api/workflow-runs/:runId/resume")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    CreateRunResponse resumeRun(@PathParam("runId") String runId, ResumeRunRequest request);

    // Start a new run that resumes a finished/failed run from an intermediate node: the node and its forward cone
    // re-run, everything upstream is reused from the source run. Returns the new run's id.
    @POST
    @Path("/api/workflow-runs/:runId/resume-from-node")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    CreateRunResponse resumeRunFromNode(@PathParam("runId") String runId, ResumeFromNodeRequest request);
}
