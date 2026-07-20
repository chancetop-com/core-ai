package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link WorkflowJournal} for driving the advance loop under a real thread pool in tests.
 *
 * @author Xander
 */
final class InMemoryWorkflowJournal implements WorkflowJournal {
    private static String key(String runId, String nodeId) {
        return runId + "|" + nodeId;
    }

    private static WorkflowNodeRun make(String runId, String nodeId, NodeRunStatus status) {
        var nodeRun = new WorkflowNodeRun();
        nodeRun.runId = runId;
        nodeRun.nodeId = nodeId;
        nodeRun.scopePathKey = "";
        nodeRun.status = status;
        return nodeRun;
    }

    private final Map<String, WorkflowNodeRun> byId = new ConcurrentHashMap<>();

    @Override
    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return new ArrayList<>(byId.values());
    }

    @Override
    public boolean appendRunning(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        return byId.putIfAbsent(key(run.id, node.id()), make(run.id, node.id(), NodeRunStatus.RUNNING)) == null;
    }

    @Override
    public void recordInput(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, String inputJson) {
        WorkflowNodeRun nodeRun = byId.get(key(run.id, node.id()));
        if (nodeRun != null) {
            nodeRun.inputJson = inputJson;
        }
    }

    @Override
    public void recordOutcome(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, NodeOutcome outcome) {
        WorkflowNodeRun nodeRun = byId.get(key(run.id, node.id()));
        switch (outcome) {
            case NodeOutcome.Normal normal -> {
                nodeRun.status = NodeRunStatus.COMPLETED;
                nodeRun.output = normal.output();
                nodeRun.childRunId = normal.childRunId();
                nodeRun.traceMetadata = normal.traceMetadata();
            }
            case NodeOutcome.Branch branch -> {
                nodeRun.status = NodeRunStatus.COMPLETED;
                nodeRun.output = branch.output();
                nodeRun.chosenEdgeIds = branch.chosenEdgeIds();
            }
            case NodeOutcome.Fail fail -> {
                nodeRun.status = NodeRunStatus.FAILED_RETRYABLE;
                nodeRun.error = fail.error();
                nodeRun.errorStack = fail.errorStack();
                nodeRun.childRunId = fail.childRunId();
                nodeRun.traceMetadata = fail.traceMetadata();
            }
            case NodeOutcome.Waiting waiting -> {
                nodeRun.status = NodeRunStatus.WAITING;
                nodeRun.inputJson = waiting.ask();
            }
            case NodeOutcome.Suspended suspended -> {
                // park like Waiting but keep the child run id linking the two-layer run.
                nodeRun.status = NodeRunStatus.WAITING;
                nodeRun.childRunId = suspended.childRunId();
            }
        }
    }

    @Override
    public void appendSkipped(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        byId.put(key(run.id, node.id()), make(run.id, node.id(), NodeRunStatus.SKIPPED));
    }

    void seedCompleted(String runId, String nodeId) {
        byId.put(key(runId, nodeId), make(runId, nodeId, NodeRunStatus.COMPLETED));
    }

    NodeRunStatus status(String runId, String nodeId) {
        WorkflowNodeRun nodeRun = byId.get(key(runId, nodeId));
        return nodeRun == null ? null : nodeRun.status;
    }

    String errorStack(String runId, String nodeId) {
        WorkflowNodeRun nodeRun = byId.get(key(runId, nodeId));
        return nodeRun == null ? null : nodeRun.errorStack;
    }

    String childRunId(String runId, String nodeId) {
        WorkflowNodeRun nodeRun = byId.get(key(runId, nodeId));
        return nodeRun == null ? null : nodeRun.childRunId;
    }

    String childTraceId(String runId, String nodeId) {
        WorkflowNodeRun nodeRun = byId.get(key(runId, nodeId));
        return nodeRun == null || nodeRun.traceMetadata == null ? null : nodeRun.traceMetadata.childTraceId;
    }

    String inputJson(String runId, String nodeId) {
        WorkflowNodeRun nodeRun = byId.get(key(runId, nodeId));
        return nodeRun == null ? null : nodeRun.inputJson;
    }
}
