package ai.core.server.web;

import ai.core.api.server.workflow.ArtifactView;
import ai.core.api.server.workflow.CreateRunRequest;
import ai.core.api.server.workflow.CreateRunResponse;
import ai.core.api.server.workflow.CreateWorkflowRequest;
import ai.core.api.server.workflow.ListNodeRunsResponse;
import ai.core.api.server.workflow.ListWorkflowRunsResponse;
import ai.core.api.server.workflow.ListWorkflowsResponse;
import ai.core.api.server.workflow.NodeRunView;
import ai.core.api.server.workflow.ResumeRunRequest;
import ai.core.api.server.workflow.UpdateWorkflowRequest;
import ai.core.api.server.workflow.ValidateWorkflowResponse;
import ai.core.api.server.workflow.WorkflowRunGraphResponse;
import ai.core.api.server.workflow.WorkflowRunView;
import ai.core.api.server.workflow.WorkflowView;
import ai.core.api.server.workflow.WorkflowWebService;
import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.workflow.WorkflowDefinitionService;
import ai.core.server.workflow.WorkflowPublishService;
import ai.core.server.workflow.WorkflowRunService;
import ai.core.server.workflow.WorkflowRunner;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

import java.util.List;

/**
 * @author Xander
 */
public class WorkflowWebServiceImpl implements WorkflowWebService {
    private static final long SYNC_TIMEOUT_MS = 120_000;        // cap a synchronous external run at 2 minutes
    private static final long SYNC_POLL_INTERVAL_MS = 400;

    @Inject
    WebContext webContext;

    @Inject
    WorkflowDefinitionService definitionService;

    @Inject
    WorkflowPublishService publishService;

    @Inject
    WorkflowRunService runService;

    @Inject
    WorkflowRunner runner;

    @Override
    public ListWorkflowsResponse list() {
        var userId = AuthContext.userId(webContext);
        var response = new ListWorkflowsResponse();
        response.workflows = definitionService.list(userId).stream().map(WorkflowWebServiceImpl::toView).toList();
        return response;
    }

    @Override
    public WorkflowView create(CreateWorkflowRequest request) {
        var userId = AuthContext.userId(webContext);
        return toView(definitionService.create(request.name, request.mode, request.graph, userId));
    }

    @Override
    public WorkflowView get(String id) {
        var userId = AuthContext.userId(webContext);
        return toView(definitionService.get(id, userId));
    }

    @Override
    public WorkflowView update(String id, UpdateWorkflowRequest request) {
        var userId = AuthContext.userId(webContext);
        return toView(definitionService.update(id, request.name, request.graph, userId));
    }

    @Override
    public void delete(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        definitionService.delete(id, userId);
    }

    @Override
    public ValidateWorkflowResponse validate(String id) {
        var userId = AuthContext.userId(webContext);
        List<String> errors = publishService.validate(definitionService.get(id, userId));
        var response = new ValidateWorkflowResponse();
        response.valid = errors.isEmpty();
        response.errors = errors;
        return response;
    }

    @Override
    public WorkflowView publish(String id) {
        var userId = AuthContext.userId(webContext);
        definitionService.get(id, userId);   // ownership check before publishing
        publishService.publish(id, userId);
        return toView(definitionService.get(id, userId));
    }

    @Override
    public CreateRunResponse createRun(String id, CreateRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        WorkflowRun run = runService.createRun(id, request.input, TriggerType.API, userId);
        runner.submit(run.id);   // start immediately; the runner job is the durability/recovery fallback, not the starter
        var response = new CreateRunResponse();
        response.runId = run.id;
        response.status = run.status.name();
        return response;
    }

    @Override
    public WorkflowRunView runSync(String id, CreateRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        WorkflowRun run = runService.createRun(id, request.input, TriggerType.API, userId);
        runner.submit(run.id);   // drive immediately instead of waiting for the runner job's next tick
        long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
        WorkflowRun latest = run;
        while (System.currentTimeMillis() < deadline) {
            latest = runService.getRun(run.id, userId);
            if (latest.status != RunStatus.PENDING && latest.status != RunStatus.RUNNING) {
                break;   // terminal: COMPLETED / FAILED / TIMEOUT / CANCELLED
            }
            try {
                Thread.sleep(SYNC_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return toRunViewWithPending(latest);
    }

    @Override
    public CreateRunResponse createPreviewRun(String id, CreateRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        WorkflowRun run = runService.createPreviewRun(id, request.input, TriggerType.API, userId);
        runner.submit(run.id);   // start immediately so Test runs don't wait for the runner job's next tick
        var response = new CreateRunResponse();
        response.runId = run.id;
        response.status = run.status.name();
        return response;
    }

    @Override
    public ListWorkflowRunsResponse listRuns(String id) {
        var userId = AuthContext.userId(webContext);
        var response = new ListWorkflowRunsResponse();
        response.runs = runService.listRuns(id, userId).stream().map(WorkflowWebServiceImpl::toRunView).toList();
        return response;
    }

    @Override
    public WorkflowRunView getRun(String runId) {
        var userId = AuthContext.userId(webContext);
        return toRunViewWithPending(runService.getRun(runId, userId));
    }

    // single-run reads attach the resume contract of a PAUSED run; list endpoints stay cheap (no node-run query)
    private WorkflowRunView toRunViewWithPending(WorkflowRun run) {
        WorkflowRunView view = toRunView(run);
        var pending = runService.pendingInputs(run);
        if (!pending.isEmpty()) {
            view.pendingInputs = pending;
        }
        return view;
    }

    @Override
    public ListNodeRunsResponse listNodeRuns(String runId) {
        var userId = AuthContext.userId(webContext);
        var response = new ListNodeRunsResponse();
        response.nodeRuns = runService.listNodeRuns(runId, userId).stream().map(WorkflowWebServiceImpl::toNodeRunView).toList();
        return response;
    }

    @Override
    public WorkflowRunGraphResponse runGraph(String runId) {
        var userId = AuthContext.userId(webContext);
        var response = new WorkflowRunGraphResponse();
        response.graph = runService.getRunGraph(runId, userId);
        return response;
    }

    @Override
    public CreateRunResponse resumeRun(String runId, ResumeRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_run_id", runId);
        runService.resume(runId, request.nodeId, request.approve, request.input, userId);
        runner.submit(runId);   // drive immediately; the runner job is the fallback, not the starter
        var response = new CreateRunResponse();
        response.runId = runId;
        response.status = RunStatus.PENDING.name();
        return response;
    }

    private static WorkflowView toView(WorkflowDefinition definition) {
        var view = new WorkflowView();
        view.id = definition.id;
        view.name = definition.name;
        view.mode = definition.mode != null ? definition.mode.name() : null;
        view.status = definition.publishedVersionId != null ? "PUBLISHED" : "DRAFT";
        view.publishedVersion = definition.publishedVersion;
        view.publishedVersionId = definition.publishedVersionId;
        view.draftGraph = definition.draftGraph;
        return view;
    }

    private static WorkflowRunView toRunView(WorkflowRun run) {
        var view = new WorkflowRunView();
        view.id = run.id;
        view.workflowId = run.workflowId;
        view.status = run.status != null ? run.status.name() : null;
        view.input = run.input;
        view.output = run.output;
        view.artifacts = toArtifactViews(run.artifacts);
        view.error = run.error;
        view.startedAt = run.startedAt;
        view.completedAt = run.completedAt;
        return view;
    }

    private static NodeRunView toNodeRunView(WorkflowNodeRun nodeRun) {
        var view = new NodeRunView();
        view.nodeId = nodeRun.nodeId;
        view.nodeType = nodeRun.nodeType;
        view.status = nodeRun.status != null ? nodeRun.status.name() : null;
        view.input = nodeRun.inputJson;
        view.output = nodeRun.output;
        view.artifacts = toArtifactViews(nodeRun.artifacts);
        view.error = nodeRun.error;
        view.childRunId = nodeRun.childRunId;
        view.startedAt = nodeRun.startedAt;
        view.completedAt = nodeRun.completedAt;
        return view;
    }

    private static List<ArtifactView> toArtifactViews(List<ArtifactRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream().map(WorkflowWebServiceImpl::toArtifactView).toList();
    }

    private static ArtifactView toArtifactView(ArtifactRef ref) {
        var view = new ArtifactView();
        view.fileId = ref.fileId;
        view.fileName = ref.fileName;
        view.contentType = ref.contentType;
        view.size = ref.size;
        view.url = ref.url;
        view.title = ref.title;
        view.description = ref.description;
        return view;
    }
}
