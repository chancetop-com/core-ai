package ai.core.server.web;

import ai.core.api.server.workflow.ArtifactView;
import ai.core.api.server.workflow.CloneWorkflowResponse;
import ai.core.api.server.workflow.CreateRunRequest;
import ai.core.api.server.workflow.CreateRunResponse;
import ai.core.api.server.workflow.CreateWorkflowRequest;
import ai.core.api.server.workflow.ExploreWorkflowsRequest;
import ai.core.api.server.workflow.ExploreWorkflowsResponse;
import ai.core.api.server.workflow.ExportWorkflowResponse;
import ai.core.api.server.workflow.ImportWorkflowRequest;
import ai.core.api.server.workflow.ImportWorkflowResponse;
import ai.core.api.server.workflow.ListNodeRunsResponse;
import ai.core.api.server.workflow.ListWorkflowRunsResponse;
import ai.core.api.server.workflow.ListWorkflowVersionsResponse;
import ai.core.api.server.workflow.ListWorkflowsRequest;
import ai.core.api.server.workflow.ListWorkflowsResponse;
import ai.core.api.server.workflow.NodeRunView;
import ai.core.api.server.workflow.ResumeFromNodeRequest;
import ai.core.api.server.workflow.ResumeRunRequest;
import ai.core.api.server.workflow.UnresolvedReferenceView;
import ai.core.api.server.workflow.UpdateWorkflowRequest;
import ai.core.api.server.workflow.ValidateWorkflowResponse;
import ai.core.api.server.workflow.WorkflowRunGraphResponse;
import ai.core.api.server.workflow.WorkflowRunView;
import ai.core.api.server.workflow.WorkflowVersionView;
import ai.core.api.server.workflow.WorkflowView;
import ai.core.api.server.workflow.WorkflowWebService;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.User;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.workflow.WorkflowDefinitionService;
import ai.core.server.workflow.WorkflowPortService;
import ai.core.server.workflow.WorkflowPublishService;
import ai.core.server.workflow.WorkflowRunService;
import ai.core.server.workflow.WorkflowRunner;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Xander
 */
public class WorkflowWebServiceImpl implements WorkflowWebService {
    private static final long SYNC_TIMEOUT_MS = 120_000;        // cap a synchronous external run at 2 minutes
    private static final long SYNC_POLL_INTERVAL_MS = 400;
    private static final int EXPLORE_DEFAULT_LIMIT = 24;

    @Inject
    WebContext webContext;

    @Inject
    WorkflowDefinitionService definitionService;

    @Inject
    MongoCollection<User> userCollection;

    @Inject
    WorkflowPublishService publishService;

    @Inject
    WorkflowRunService runService;

    @Inject
    WorkflowRunner runner;

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    @Inject
    WorkflowPortService portService;

    @Override
    public ListWorkflowsResponse list(ListWorkflowsRequest request) {
        var userId = AuthContext.userId(webContext);
        Boolean myWorkflows = null;
        if (request != null && request.myWorkflows != null) {
            myWorkflows = "true".equalsIgnoreCase(request.myWorkflows) || "1".equals(request.myWorkflows);
        }
        var keyword = request == null ? null : request.keyword;
        var offset = request == null ? null : request.offset;
        var limit = request == null ? null : request.limit;
        var definitions = definitionService.list(userId, myWorkflows, keyword, offset, limit);
        var userNames = resolveUserNames(definitions);
        var response = new ListWorkflowsResponse();
        response.workflows = definitions.stream().map(d -> {
            var view = toView(d, userNames.get(d.userId));
            view.editable = userId.equals(d.userId);   // caller userId is non-null; tolerates a null d.userId row
            view.draftGraph = null;   // the list UI never reads the graph — and must never ship another owner's draft
            return view;
        }).toList();
        response.total = definitionService.listCount(userId, myWorkflows, keyword);
        response.offset = offset;
        response.limit = limit;
        return response;
    }

    @Override
    public ExploreWorkflowsResponse explore(ExploreWorkflowsRequest request) {
        var userId = AuthContext.userId(webContext);
        var keyword = request == null ? null : request.keyword;
        int offset = request != null && request.offset != null ? request.offset : 0;
        int limit = request != null && request.limit != null ? request.limit : EXPLORE_DEFAULT_LIMIT;

        var definitions = definitionService.explore(userId, keyword, offset, limit);
        var userNames = resolveUserNames(definitions);
        var response = new ExploreWorkflowsResponse();
        response.workflows = definitions.stream().map(d -> {
            var view = toView(d, userNames.get(d.userId));
            view.editable = false;       // explore only ever returns other users' published workflows
            view.draftGraph = null;      // list payload never carries the graph
            return view;
        }).toList();
        response.total = definitionService.exploreCount(userId, keyword);
        return response;
    }

    // Batch-resolve owner display names so the discover list can show the author without an N+1 query.
    private Map<String, String> resolveUserNames(List<WorkflowDefinition> definitions) {
        Set<String> userIds = definitions.stream().map(d -> d.userId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (userIds.isEmpty()) return Map.of();
        var map = new HashMap<String, String>();
        for (User user : userCollection.find(Filters.in("_id", userIds))) {
            map.put(user.id, user.name);
        }
        return map;
    }

    private boolean isAdmin(String userId) {
        return userCollection.get(userId).map(user -> "admin".equals(user.role)).orElse(false);
    }

    @Override
    public CloneWorkflowResponse clone(String id) {
        var userId = AuthContext.userId(webContext);
        var definition = definitionService.clone(id, userId);
        var response = new CloneWorkflowResponse();
        response.workflow = toView(definition);
        // Validate the clone AS THE CALLER: a previously-valid published graph only fails on agent ownership now,
        // so this surfaces exactly the AGENT/LLM nodes the caller must replace before they can Test/Publish.
        response.warnings = publishService.validate(definition);
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
        var definition = definitionService.getReadable(id, userId);   // owner: editable draft; other user: read-only published
        boolean editable = definition.userId.equals(userId);
        var ownerName = editable ? null : userCollection.get(definition.userId).map(u -> u.name).orElse(null);
        var view = toView(definition, ownerName);
        view.editable = editable;
        return view;
    }

    @Override
    public ExportWorkflowResponse export(String id) {
        var userId = AuthContext.userId(webContext);
        return portService.export(id, userId);
    }

    @Override
    public ImportWorkflowResponse importWorkflow(ImportWorkflowRequest request) {
        var userId = AuthContext.userId(webContext);
        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(request.content, request.name, userId);
        var response = new ImportWorkflowResponse();
        response.workflow = toView(result.definition());
        response.unresolvedReferences = result.unresolved().stream().map(WorkflowWebServiceImpl::toUnresolvedView).toList();
        return response;
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
        definitionService.delete(id, userId, isAdmin(userId));
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
    public ListWorkflowVersionsResponse listVersions(String id) {
        var userId = AuthContext.userId(webContext);
        WorkflowDefinition definition = definitionService.getReadable(id, userId);
        var response = new ListWorkflowVersionsResponse();
        response.versions = publishService.listVersions(id, userId).stream()
            .map(version -> toVersionView(version, definition.publishedVersionId, WorkflowDefinitionService.isPublicActive(definition)))
            .toList();
        return response;
    }

    @Override
    public WorkflowVersionView saveVersion(String id) {
        var userId = AuthContext.userId(webContext);
        WorkflowPublishedVersion version = publishService.saveVersion(id, userId);
        WorkflowDefinition definition = definitionService.get(id, userId);
        return toVersionView(version, definition.publishedVersionId, WorkflowDefinitionService.isPublicActive(definition));
    }

    @Override
    public WorkflowView publishVersion(String id, String versionId) {
        var userId = AuthContext.userId(webContext);
        return toView(publishService.publishVersion(id, versionId, userId));
    }

    @Override
    public WorkflowView restoreVersion(String id, String versionId) {
        var userId = AuthContext.userId(webContext);
        return toView(publishService.restoreVersionToDraft(id, versionId, userId));
    }

    @Override
    public WorkflowView unpublish(String id) {
        var userId = AuthContext.userId(webContext);
        return toView(publishService.unpublish(id, userId));
    }

    @Override
    public CreateRunResponse createRun(String id, CreateRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        WorkflowRun run = runService.createRun(id, request.input, TriggerType.API, userId, runVisibility(request));
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
        WorkflowRun run = runService.createRun(id, request.input, TriggerType.API, userId, runVisibility(request));
        runner.submit(run.id);   // drive immediately instead of waiting for the runner job's next tick
        long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
        WorkflowRun latest = run;
        while (System.currentTimeMillis() < deadline) {
            latest = runService.getRun(run.id, userId);
            if (latest.status == RunStatus.PAUSED && runService.pendingInputs(latest).isEmpty()) {
                // WORKFLOW-node waits park the parent run as PAUSED while the child workflow runs; keep sync callers
                // blocked until the child terminal callback wakes the parent, or until the sync timeout is reached.
            } else if (latest.status != RunStatus.PENDING && latest.status != RunStatus.RUNNING) {
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

    private static WorkflowVisibility runVisibility(CreateRunRequest request) {
        if (request == null || request.visibility == null || request.visibility.isBlank()) {
            return WorkflowVisibility.PRIVATE;
        }
        try {
            return WorkflowVisibility.valueOf(request.visibility.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid run visibility: " + request.visibility);
        }
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
        response.nodeRuns = runService.listNodeRuns(runId, userId).stream().map(this::toNodeRunView).toList();
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
    public WorkflowRunGraphResponse versionGraph(String versionId) {
        var userId = AuthContext.userId(webContext);
        var response = new WorkflowRunGraphResponse();
        response.graph = definitionService.getVersionGraph(versionId, userId);
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

    @Override
    public CreateRunResponse resumeRunFromNode(String runId, ResumeFromNodeRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_run_id", runId);
        WorkflowRun run = runService.resumeFromNode(runId, request.nodeId, userId);
        runner.submit(run.id);   // drive immediately; the runner job is the fallback, not the starter
        var response = new CreateRunResponse();
        response.runId = run.id;
        response.status = run.status.name();
        return response;
    }

    private static UnresolvedReferenceView toUnresolvedView(WorkflowPortService.UnresolvedReference ref) {
        var view = new UnresolvedReferenceView();
        view.nodeId = ref.nodeId();
        view.nodeType = ref.nodeType();
        view.refType = ref.refType();
        view.refId = ref.refId();
        view.message = ref.message();
        return view;
    }

    private static WorkflowView toView(WorkflowDefinition definition) {
        return toView(definition, null);
    }

    private static WorkflowView toView(WorkflowDefinition definition, String userName) {
        var view = new WorkflowView();
        view.id = definition.id;
        view.userId = definition.userId;
        view.userName = userName;
        view.name = definition.name;
        view.mode = definition.mode != null ? definition.mode.name() : null;
        var status = WorkflowDefinitionService.statusOf(definition);
        var visibility = WorkflowDefinitionService.visibilityOf(definition);
        view.visibility = visibility.name();
        view.status = status.name();
        if ("ACTIVE".equals(view.status)) {
            view.status = visibility == WorkflowVisibility.PUBLIC && definition.publishedVersionId != null ? "PUBLIC" : "PRIVATE";
        }
        view.publishedVersion = definition.publishedVersion;
        view.publishedVersionId = definition.publishedVersionId;
        view.draftGraph = definition.draftGraph;
        return view;
    }

    private static WorkflowVersionView toVersionView(WorkflowPublishedVersion version, String currentPublicVersionId, boolean publicActive) {
        var view = new WorkflowVersionView();
        view.id = version.id;
        view.workflowId = version.workflowId;
        view.version = version.version;
        view.preview = version.preview;
        view.status = version.status != null ? version.status.name() : "ACTIVE";
        view.sha256 = version.sha256;
        view.publishedBy = version.publishedBy;
        view.publishedAt = version.publishedAt;
        view.currentPublic = publicActive && version.id.equals(currentPublicVersionId);
        return view;
    }

    private static WorkflowRunView toRunView(WorkflowRun run) {
        var view = new WorkflowRunView();
        view.id = run.id;
        view.workflowId = run.workflowId;
        view.status = run.status != null ? run.status.name() : null;
        view.visibility = WorkflowRunService.visibilityOf(run.visibility).name();
        view.input = run.input;
        view.output = run.output;
        view.artifacts = toArtifactViews(run.artifacts);
        view.error = run.error;
        view.startedAt = run.startedAt;
        view.completedAt = run.completedAt;
        view.resumedFromRunId = run.resumedFromRunId;
        view.resumeFromNodeId = run.resumeFromNodeId;
        view.parentRunId = run.parentRunId;
        view.parentNodeId = run.parentNodeId;
        return view;
    }

    private NodeRunView toNodeRunView(WorkflowNodeRun nodeRun) {
        var view = new NodeRunView();
        view.nodeId = nodeRun.nodeId;
        view.nodeType = nodeRun.nodeType;
        view.status = nodeRun.status != null ? nodeRun.status.name() : null;
        view.input = nodeRun.inputJson;
        view.output = nodeRun.output;
        view.artifacts = toArtifactViews(nodeRun.artifacts);
        view.error = nodeRun.error;
        view.childRunId = nodeRun.childRunId;
        if (nodeRun.childRunId != null && !nodeRun.childRunId.isBlank()) {
            if ("WORKFLOW".equals(nodeRun.nodeType)) {
                view.childRunType = "WORKFLOW";
                runCollection.get(nodeRun.childRunId).ifPresent(child -> view.childWorkflowId = child.workflowId);
            } else {
                view.childRunType = "AGENT";
                agentRunCollection.get(nodeRun.childRunId).ifPresent(child -> view.traceId = child.traceId);
            }
        }
        view.spanId = nodeRun.spanId;
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
