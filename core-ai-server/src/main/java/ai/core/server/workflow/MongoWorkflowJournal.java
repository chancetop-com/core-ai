package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Mongo-backed journal. Race-safe append relies on the deterministic _id (run|node|scope): a concurrent insert
 * of the same node-run hits the implicit _id unique constraint (duplicate-key 11000), the same idiom as
 * IngestService.saveSpan. The (run_id, node_id, scope_path_key) secondary index serves the run_id fold query only.
 *
 * @author Xander
 */
public class MongoWorkflowJournal implements WorkflowJournal {
    private static final int DUPLICATE_KEY = 11000;

    private final MongoCollection<WorkflowNodeRun> collection;

    public MongoWorkflowJournal(MongoCollection<WorkflowNodeRun> collection) {
        this.collection = collection;
    }

    @Override
    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return collection.find(Filters.eq("run_id", runId));
    }

    @Override
    public boolean appendRunning(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        WorkflowNodeRun nodeRun = newNodeRun(run, node, scopePath, NodeRunStatus.RUNNING);
        try {
            collection.insert(nodeRun);
            return true;
        } catch (MongoWriteException e) {
            if (e.getCode() == DUPLICATE_KEY) {
                return false;   // another dispatcher already claimed this node-run
            }
            throw e;
        }
    }

    @Override
    public void recordInput(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, String inputJson) {
        String id = nodeRunId(run.id, node.id(), scopePath);
        WorkflowNodeRun nodeRun = collection.get(id).orElseThrow(() -> new IllegalStateException("node-run not found: " + id));
        nodeRun.inputJson = inputJson;
        collection.replace(nodeRun);
    }

    @Override
    public void recordOutcome(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, NodeOutcome outcome) {
        String id = nodeRunId(run.id, node.id(), scopePath);
        WorkflowNodeRun nodeRun = collection.get(id).orElseThrow(() -> new IllegalStateException("node-run not found: " + id));
        switch (outcome) {
            case NodeOutcome.Normal normal -> {
                nodeRun.status = NodeRunStatus.COMPLETED;
                nodeRun.output = normal.output();
                nodeRun.childRunId = normal.childRunId();
                nodeRun.artifacts = normal.artifacts().isEmpty() ? null : List.copyOf(normal.artifacts());
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
                nodeRun.childRunId = fail.childRunId();
            }
            case NodeOutcome.Waiting waiting -> {
                // park: out-edges stay PENDING (WAITING -> running fact). The resume endpoint later flips this to
                // COMPLETED with the human's output/decision. input_json holds the rendered ask for the UI.
                nodeRun.status = NodeRunStatus.WAITING;
                nodeRun.inputJson = waiting.ask();
            }
        }
        nodeRun.completedAt = ZonedDateTime.now();
        collection.replace(nodeRun);
    }

    /**
     * Seed a frozen prefix node-run into a new (resume) run: copy a source run's terminal fact verbatim under the
     * target run's id, so the planner re-derives the exact same edge verdicts and the resume frontier lands on the
     * chosen node. Only COMPLETED / SKIPPED facts are seeded (the re-run set carries no fact and runs fresh); the
     * deterministic _id keeps it idempotent if a seed is retried.
     */
    public void seed(String targetRunId, WorkflowNodeRun prior) {
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
        seeded.attempt = prior.attempt;
        seeded.startedAt = now;
        seeded.completedAt = now;
        seeded.createdAt = now;
        try {
            collection.insert(seeded);
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY) {
                throw e;
            }
        }
    }

    @Override
    public void appendSkipped(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        WorkflowNodeRun nodeRun = newNodeRun(run, node, scopePath, NodeRunStatus.SKIPPED);
        nodeRun.completedAt = nodeRun.startedAt;
        try {
            collection.insert(nodeRun);
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY) {
                throw e;
            }
        }
    }

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
        nodeRun.startedAt = now;
        nodeRun.createdAt = now;
        return nodeRun;
    }

    private static String nodeRunId(String runId, String nodeId, List<ScopeFrame> scopePath) {
        return runId + "|" + nodeId + "|" + ScopeFrame.canonicalKey(scopePath);
    }
}
