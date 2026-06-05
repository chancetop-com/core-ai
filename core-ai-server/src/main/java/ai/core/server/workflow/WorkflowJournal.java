package ai.core.server.workflow;

import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.List;

/**
 * The persistence seam the advance loop drives: read the node-runs to fold, and append/settle node-runs.
 * Mongo-backed in production ({@link MongoWorkflowJournal}); faked in tests so the drive loop is unit-testable.
 *
 * @author Xander
 */
public interface WorkflowJournal {
    List<WorkflowNodeRun> nodeRuns(String runId);

    /** Insert a RUNNING node-run; false if one already exists at this (run, node, scope) — race-safe. */
    boolean appendRunning(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath);

    /** Settle the existing node-run with the executor's outcome (COMPLETED / FAILED_RETRYABLE). */
    void recordOutcome(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath, NodeOutcome outcome);

    /** Append a SKIPPED node-run so the skip propagates on the next plan pass. */
    void appendSkipped(WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath);
}
