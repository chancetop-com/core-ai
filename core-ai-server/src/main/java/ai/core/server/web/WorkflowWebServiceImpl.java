package ai.core.server.web;

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
import ai.core.api.server.workflow.ListWorkflowAgentOptionsRequest;
import ai.core.api.server.workflow.ListWorkflowAgentOptionsResponse;
import ai.core.api.server.workflow.ListWorkflowRunsResponse;
import ai.core.api.server.workflow.ListWorkflowVersionsResponse;
import ai.core.api.server.workflow.ListWorkflowsRequest;
import ai.core.api.server.workflow.ListWorkflowsResponse;
import ai.core.api.server.workflow.ResumeFromNodeRequest;
import ai.core.api.server.workflow.ResumeRunRequest;
import ai.core.api.server.workflow.UpdateWorkflowRequest;
import ai.core.api.server.workflow.ValidateWorkflowResponse;
import ai.core.api.server.workflow.WorkflowRunGraphResponse;
import ai.core.api.server.workflow.WorkflowRunView;
import ai.core.api.server.workflow.WorkflowVersionView;
import ai.core.api.server.workflow.WorkflowView;
import ai.core.api.server.workflow.WorkflowWebService;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.User;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowDefinitionStatus;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.workflow.WorkflowAgentOptionService;
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
    WorkflowAgentOptionService agentOptionService;

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
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public ListWorkflowsResponse list(ListWorkflowsRequest request) {
        var userId = AuthContext.userId(webContext);
        Boolean myWorkflows = null;
        if (request != null && request.myWorkflows != null) {
            myWorkflows = "true".equalsIgnoreCase(request.myWorkflows) || "1".equals(request.myWorkflows);
        }
        var keyword = request == null ? null : request.keyword;
        var offset = request == null ? null : request.offset;
        var limit = request == null ? null : request.limit;
        boolean archived = request != null && Boolean.TRUE.equals(request.archived);
        var definitions = archived
            ? definitionService.listArchived(userId, keyword, offset, limit)
            : definitionService.list(userId, myWorkflows, keyword, offset, limit);
        var userNames = resolveUserNames(definitions);
        var response = new ListWorkflowsResponse();
        response.workflows = definitions.stream().map(d -> {
            var view = WorkflowViewMapper.toView(d, userNames.get(d.userId));
            // archived workflows open read-only even for the owner; caller userId is non-null, tolerates a null d.userId row
            view.editable = !archived && userId.equals(d.userId);
            view.draftGraph = null;   // the list UI never reads the graph — and must never ship another owner's draft
            return view;
        }).toList();
        response.total = archived
            ? definitionService.listArchivedCount(userId, keyword)
            : definitionService.listCount(userId, myWorkflows, keyword);
        response.offset = offset;
        response.limit = limit;
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public ListWorkflowAgentOptionsResponse agentOptions(ListWorkflowAgentOptionsRequest request) {
        return agentOptionService.list(AuthContext.userId(webContext), request);
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public ExploreWorkflowsResponse explore(ExploreWorkflowsRequest request) {
        var userId = AuthContext.userId(webContext);
        var keyword = request == null ? null : request.keyword;
        int offset = request != null && request.offset != null ? request.offset : 0;
        int limit = request != null && request.limit != null ? request.limit : EXPLORE_DEFAULT_LIMIT;

        var definitions = definitionService.explore(userId, keyword, offset, limit);
        var userNames = resolveUserNames(definitions);
        var response = new ExploreWorkflowsResponse();
        response.workflows = definitions.stream().map(d -> {
            var view = WorkflowViewMapper.toView(d, userNames.get(d.userId));
            view.editable = Boolean.FALSE;       // explore only ever returns other users' published workflows
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
        return userCollection.get(userId).map(user -> "admin".equals(user.role)).orElse(Boolean.FALSE);
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public CloneWorkflowResponse clone(String id) {
        var userId = AuthContext.userId(webContext);
        var definition = definitionService.clone(id, userId);
        var response = new CloneWorkflowResponse();
        response.workflow = WorkflowViewMapper.toView(definition);
        // Validate the clone AS THE CALLER: a previously-valid published graph only fails on agent ownership now,
        // so this surfaces exactly the AGENT/LLM nodes the caller must replace before they can Test/Publish.
        response.warnings = publishService.validate(definition);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowView create(CreateWorkflowRequest request) {
        var userId = AuthContext.userId(webContext);
        return WorkflowViewMapper.toView(definitionService.create(request.name, request.mode, request.graph, userId));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public WorkflowView get(String id) {
        var userId = AuthContext.userId(webContext);
        var definition = definitionService.getReadable(id, userId);   // owner: editable draft; other user: read-only published
        // an archived workflow opens read-only even for its owner (update would 409); it exists only for run history
        boolean editable = definition.userId.equals(userId)
            && WorkflowDefinitionService.statusOf(definition) != WorkflowDefinitionStatus.ARCHIVED;
        var ownerName = editable ? null : userCollection.get(definition.userId).map(u -> u.name).orElse(null);
        var view = WorkflowViewMapper.toView(definition, ownerName);
        view.editable = editable;
        return view;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public ExportWorkflowResponse export(String id) {
        var userId = AuthContext.userId(webContext);
        return portService.export(id, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public ImportWorkflowResponse importWorkflow(ImportWorkflowRequest request) {
        var userId = AuthContext.userId(webContext);
        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(request.content, request.name, userId);
        var response = new ImportWorkflowResponse();
        response.workflow = WorkflowViewMapper.toView(result.definition());
        response.unresolvedReferences = result.unresolved().stream().map(WorkflowViewMapper::toUnresolvedView).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowView update(String id, UpdateWorkflowRequest request) {
        var userId = AuthContext.userId(webContext);
        return WorkflowViewMapper.toView(definitionService.update(id, request.name, request.graph, userId));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public void delete(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        definitionService.delete(id, userId, isAdmin(userId));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public ValidateWorkflowResponse validate(String id) {
        var userId = AuthContext.userId(webContext);
        List<String> errors = publishService.validate(definitionService.get(id, userId));
        var response = new ValidateWorkflowResponse();
        response.valid = errors.isEmpty();
        response.errors = errors;
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowView publish(String id) {
        var userId = AuthContext.userId(webContext);
        definitionService.get(id, userId);   // ownership check before publishing
        publishService.publish(id, userId);
        return WorkflowViewMapper.toView(definitionService.get(id, userId));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public ListWorkflowVersionsResponse listVersions(String id) {
        var userId = AuthContext.userId(webContext);
        WorkflowDefinition definition = definitionService.getReadable(id, userId);
        var response = new ListWorkflowVersionsResponse();
        response.versions = publishService.listVersions(id, userId).stream()
            .map(version -> WorkflowViewMapper.toVersionView(version, definition.publishedVersionId, WorkflowDefinitionService.isPublicActive(definition)))
            .toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowVersionView saveVersion(String id) {
        var userId = AuthContext.userId(webContext);
        WorkflowPublishedVersion version = publishService.saveVersion(id, userId);
        WorkflowDefinition definition = definitionService.get(id, userId);
        return WorkflowViewMapper.toVersionView(version, definition.publishedVersionId, WorkflowDefinitionService.isPublicActive(definition));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowView publishVersion(String id, String versionId) {
        var userId = AuthContext.userId(webContext);
        return WorkflowViewMapper.toView(publishService.publishVersion(id, versionId, userId));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowView restoreVersion(String id, String versionId) {
        var userId = AuthContext.userId(webContext);
        return WorkflowViewMapper.toView(publishService.restoreVersionToDraft(id, versionId, userId));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowView unpublish(String id) {
        var userId = AuthContext.userId(webContext);
        return WorkflowViewMapper.toView(publishService.unpublish(id, userId));
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public CreateRunResponse createRun(String id, CreateRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        WorkflowRun run = runService.createRun(id, request.input, TriggerType.API, userId, WorkflowViewMapper.runVisibility(request));
        runner.submit(run.id);   // start immediately; the runner job is the durability/recovery fallback, not the starter
        var response = new CreateRunResponse();
        response.runId = run.id;
        response.status = run.status.name();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
    public WorkflowRunView runSync(String id, CreateRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        WorkflowRun run = runService.createRun(id, request.input, TriggerType.API, userId, WorkflowViewMapper.runVisibility(request));
        runner.submit(run.id);   // drive immediately instead of waiting for the runner job's next tick
        long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
        WorkflowRun latest = run;
        while (System.currentTimeMillis() < deadline) {
            latest = runService.getRun(run.id, userId);
            if (latest.status == RunStatus.PAUSED && runService.pendingInputs(latest).isEmpty()) {
                // WORKFLOW-node waits park the parent run as PAUSED while the child workflow runs; keep sync callers
                // blocked until the child terminal callback wakes the parent, or until the sync timeout is reached.
                continue;
            }
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
        return WorkflowRunViewSupport.toRunViewWithPending(runService, latest);
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
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
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public ListWorkflowRunsResponse listRuns(String id) {
        var userId = AuthContext.userId(webContext);
        var response = new ListWorkflowRunsResponse();
        response.runs = runService.listRuns(id, userId, isAdmin(userId)).stream().map(WorkflowViewMapper::toRunView).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public WorkflowRunView getRun(String runId) {
        var userId = AuthContext.userId(webContext);
        return WorkflowRunViewSupport.toRunViewWithPending(runService, runService.getRun(runId, userId, isAdmin(userId)));
    }

    // single-run reads attach the resume contract of a PAUSED run; list endpoints stay cheap (no node-run query)

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public ListNodeRunsResponse listNodeRuns(String runId) {
        var userId = AuthContext.userId(webContext);
        var response = new ListNodeRunsResponse();
        response.nodeRuns = runService.listNodeRuns(runId, userId, isAdmin(userId)).stream()
            .map(nodeRun -> WorkflowRunViewSupport.toNodeRunView(nodeRun, runCollection, agentRunCollection)).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public WorkflowRunGraphResponse runGraph(String runId) {
        var userId = AuthContext.userId(webContext);
        var response = new WorkflowRunGraphResponse();
        response.graph = runService.getRunGraph(runId, userId, isAdmin(userId));
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_VIEW)
    public WorkflowRunGraphResponse versionGraph(String versionId) {
        var userId = AuthContext.userId(webContext);
        var response = new WorkflowRunGraphResponse();
        response.graph = definitionService.getVersionGraph(versionId, userId);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
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
    @PermissionsRequired(PermissionCodes.WORKFLOW_MANAGE)
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
}
