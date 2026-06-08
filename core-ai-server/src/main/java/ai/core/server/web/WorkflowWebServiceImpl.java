package ai.core.server.web;

import ai.core.api.server.workflow.CreateRunRequest;
import ai.core.api.server.workflow.CreateRunResponse;
import ai.core.api.server.workflow.CreateWorkflowRequest;
import ai.core.api.server.workflow.ListNodeRunsResponse;
import ai.core.api.server.workflow.ListWorkflowRunsResponse;
import ai.core.api.server.workflow.ListWorkflowsResponse;
import ai.core.api.server.workflow.NodeRunView;
import ai.core.api.server.workflow.UpdateWorkflowRequest;
import ai.core.api.server.workflow.ValidateWorkflowResponse;
import ai.core.api.server.workflow.WorkflowRunView;
import ai.core.api.server.workflow.WorkflowView;
import ai.core.api.server.workflow.WorkflowWebService;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.workflow.WorkflowDefinitionService;
import ai.core.server.workflow.WorkflowPublishService;
import ai.core.server.workflow.WorkflowRunService;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

import java.util.List;

/**
 * @author Xander
 */
public class WorkflowWebServiceImpl implements WorkflowWebService {
    @Inject
    WebContext webContext;

    @Inject
    WorkflowDefinitionService definitionService;

    @Inject
    WorkflowPublishService publishService;

    @Inject
    WorkflowRunService runService;

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
        var response = new CreateRunResponse();
        response.runId = run.id;
        response.status = run.status.name();
        return response;
    }

    @Override
    public CreateRunResponse createPreviewRun(String id, CreateRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("workflow_id", id);
        WorkflowRun run = runService.createPreviewRun(id, request.input, TriggerType.API, userId);
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
        return toRunView(runService.getRun(runId, userId));
    }

    @Override
    public ListNodeRunsResponse listNodeRuns(String runId) {
        var userId = AuthContext.userId(webContext);
        var response = new ListNodeRunsResponse();
        response.nodeRuns = runService.listNodeRuns(runId, userId).stream().map(WorkflowWebServiceImpl::toNodeRunView).toList();
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
        view.error = nodeRun.error;
        view.childRunId = nodeRun.childRunId;
        view.startedAt = nodeRun.startedAt;
        view.completedAt = nodeRun.completedAt;
        return view;
    }
}
