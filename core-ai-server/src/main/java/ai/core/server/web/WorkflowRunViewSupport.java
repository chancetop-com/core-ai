package ai.core.server.web;

import ai.core.api.server.workflow.NodeRunView;
import ai.core.api.server.workflow.WorkflowRunView;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.WorkflowRunService;
import core.framework.mongo.MongoCollection;

/**
 * View mapping helpers for workflow runs and node runs, kept out of
 * {@code WorkflowWebServiceImpl} to stay within the file length limit.
 *
 * @author Xander
 */
final class WorkflowRunViewSupport {
    // single-run reads attach the resume contract of a PAUSED run; list endpoints stay cheap (no node-run query)
    static WorkflowRunView toRunViewWithPending(WorkflowRunService runService, WorkflowRun run) {
        WorkflowRunView view = WorkflowViewMapper.toRunView(run);
        var pending = runService.pendingInputs(run);
        if (!pending.isEmpty()) {
            view.pendingInputs = pending;
        }
        return view;
    }

    static NodeRunView toNodeRunView(WorkflowNodeRun nodeRun, MongoCollection<WorkflowRun> runCollection, MongoCollection<AgentRun> agentRunCollection) {
        var view = new NodeRunView();
        view.nodeId = nodeRun.nodeId;
        view.nodeType = nodeRun.nodeType;
        view.status = nodeRun.status != null ? nodeRun.status.name() : null;
        view.input = nodeRun.inputJson;
        view.output = nodeRun.output;
        view.artifacts = WorkflowViewMapper.toArtifactViews(nodeRun.artifacts);
        view.error = nodeRun.error;
        view.errorStack = nodeRun.errorStack;
        view.childRunId = nodeRun.childRunId;
        view.traceMetadata = WorkflowViewMapper.toTraceMetadataView(nodeRun.traceMetadata);
        if (nodeRun.traceMetadata != null && nodeRun.traceMetadata.childTraceId != null && !nodeRun.traceMetadata.childTraceId.isBlank()) {
            view.traceId = nodeRun.traceMetadata.childTraceId;
        }
        if (nodeRun.childRunId != null && !nodeRun.childRunId.isBlank()) {
            if ("WORKFLOW".equals(nodeRun.nodeType)) {
                view.childRunType = "WORKFLOW";
                runCollection.get(nodeRun.childRunId).ifPresent(child -> view.childWorkflowId = child.workflowId);
            } else {
                view.childRunType = "AGENT";
                resolveAgentTraceId(view, nodeRun.childRunId, agentRunCollection);
            }
        }
        view.spanId = nodeRun.spanId;
        view.startedAt = nodeRun.startedAt;
        view.completedAt = nodeRun.completedAt;
        return view;
    }

    private static void resolveAgentTraceId(NodeRunView view, String childRunId, MongoCollection<AgentRun> agentRunCollection) {
        if (view.traceId == null || view.traceId.isBlank()) {
            agentRunCollection.get(childRunId).ifPresent(child -> view.traceId = child.traceId);
        }
    }

    private WorkflowRunViewSupport() {
    }
}
