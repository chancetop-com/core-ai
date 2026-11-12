package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Store-backed journal. Race-safe append relies on the unique (run_id, node_id, scope_path_key): a concurrent insert
 * of the same node-run is ignored by the store, the same idiom as an insert-if-absent.
 *
 * @author Xander
 */
public class StoreWorkflowJournal implements WorkflowJournal {
    private static WorkflowNodeRun newNodeRun(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, NodeRunStatus status) {
        var now = ZonedDateTime.now();
        var nodeRun = new WorkflowNodeRun();
        nodeRun.id = nodeRunId(run.id, node.id(), scopePath);
        nodeRun.runId = run.id;
        nodeRun.workflowId = run.workflowId;
        nodeRun.nodeId = node.id();
        nodeRun.nodeType = node.type();
        nodeRun.scopePath = scopePath.isEmpty() ? null : List.copyOf(scopePath);
        nodeRun.scopePathKey = ScopeFrame.canonicalKey(scopePath);
        nodeRun.status = status;
        nodeRun.attempt = 1;
        nodeRun.preview = run.preview;
        nodeRun.startedAt = now;
        nodeRun.createdAt = now;
        return nodeRun;
    }

    private static String nodeRunId(String runId, String nodeId, List<ScopeFrame> scopePath) {
        return runId + "|" + nodeId + "|" + ScopeFrame.canonicalKey(scopePath);
    }

    private final WorkflowNodeRunStore store;

    public StoreWorkflowJournal(WorkflowNodeRunStore store) {
        this.store = store;
    }

    @Override
    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return store.listByRun(runId);
    }

    @Override
    public boolean appendRunning(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        return store.insertIfAbsent(newNodeRun(run, node, scopePath, NodeRunStatus.RUNNING));
    }

    @Override
    public void recordInput(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, String inputJson) {
        String id = nodeRunId(run.id, node.id(), scopePath);
        WorkflowNodeRun nodeRun = store.get(id).orElseThrow(() -> new IllegalStateException("node-run not found: " + id));
        nodeRun.inputJson = inputJson;
        store.replace(nodeRun);
    }

    @Override
    public void recordOutcome(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, NodeOutcome outcome) {
        String id = nodeRunId(run.id, node.id(), scopePath);
        WorkflowNodeRun nodeRun = store.get(id).orElseThrow(() -> new IllegalStateException("node-run not found: " + id));
        switch (outcome) {
            case NodeOutcome.Normal normal -> {
                nodeRun.status = NodeRunStatus.COMPLETED;
                nodeRun.output = normal.output();
                nodeRun.childRunId = normal.childRunId();
                nodeRun.artifacts = normal.artifacts().isEmpty() ? null : List.copyOf(normal.artifacts());
                nodeRun.traceMetadata = normal.traceMetadata();
            }
            case NodeOutcome.Branch branch -> {
                nodeRun.status = NodeRunStatus.COMPLETED;
                nodeRun.output = branch.output();
                nodeRun.chosenEdgeIds = branch.chosenEdgeIds();
            }
            case NodeOutcome.Fail fail -> {
                // todo: honor fail.retryable() once NodeRunStatus has a terminal FAILED and the retry feature lands;
                // today the engine treats both identically (out-edges PENDING), so this is only a metadata gap.
                nodeRun.status = NodeRunStatus.FAILED_RETRYABLE;
                nodeRun.error = fail.error();
                nodeRun.errorStack = fail.errorStack();
                nodeRun.childRunId = fail.childRunId();
                nodeRun.traceMetadata = fail.traceMetadata();
            }
            case NodeOutcome.Waiting waiting -> {
                // park: out-edges stay PENDING (WAITING -> running fact). The resume endpoint later flips this to
                // COMPLETED with the human's output/decision. input_json holds the rendered ask for the UI.
                nodeRun.status = NodeRunStatus.WAITING;
                nodeRun.inputJson = waiting.ask();
            }
            case NodeOutcome.Suspended suspended -> {
                // park exactly like Waiting (WAITING projects to a running fact -> run PAUSED), but keep the child
                // run id so the child's terminal callback can settle this node and cascade-cancel can reach it.
                nodeRun.status = NodeRunStatus.WAITING;
                nodeRun.childRunId = suspended.childRunId();
            }
        }
        nodeRun.completedAt = ZonedDateTime.now();
        store.replace(nodeRun);
    }

    /**
     * Seed a frozen prefix node-run into a new (resume) run: copy a source run's terminal fact verbatim under the
     * target run's id, so the planner re-derives the exact same edge verdicts and the resume frontier lands on the
     * chosen node. Only COMPLETED / SKIPPED facts are seeded (the re-run set carries no fact and runs fresh); the
     * deterministic id keeps it idempotent if a seed is retried.
     */
    public void seed(String targetRunId, boolean preview, WorkflowNodeRun prior) {
        var now = ZonedDateTime.now();
        var seeded = new WorkflowNodeRun();
        seeded.id = targetRunId + "|" + prior.nodeId + "|" + prior.scopePathKey;
        seeded.runId = targetRunId;
        seeded.workflowId = prior.workflowId;
        seeded.nodeId = prior.nodeId;
        seeded.nodeType = prior.nodeType;
        seeded.scopePath = prior.scopePath;
        seeded.scopePathKey = prior.scopePathKey;
        seeded.status = prior.status;
        seeded.inputJson = prior.inputJson;
        seeded.output = prior.output;
        seeded.artifacts = prior.artifacts;
        seeded.chosenEdgeIds = prior.chosenEdgeIds;
        seeded.childRunId = prior.childRunId;
        seeded.traceMetadata = prior.traceMetadata;
        seeded.attempt = prior.attempt;
        seeded.preview = preview;
        seeded.startedAt = now;
        seeded.completedAt = now;
        seeded.createdAt = now;
        store.insertIfAbsent(seeded);
    }

    @Override
    public void appendSkipped(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        WorkflowNodeRun nodeRun = newNodeRun(run, node, scopePath, NodeRunStatus.SKIPPED);
        nodeRun.completedAt = nodeRun.startedAt;
        store.insertIfAbsent(nodeRun);
    }
}
