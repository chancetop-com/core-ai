package ai.core.server.workflow;

import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.workflow.engine.NodeFact;
import ai.core.server.workflow.engine.RunState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects persisted node-runs at one scope into the engine's {@link RunState} (the durable facts the pure
 * Planner folds). The only bridge from the Mongo journal to the framework-free engine. It pins two mappings:
 * NodeRunStatus -> NodeFactStatus (FAILED_RETRYABLE/WAITING) and chosen_edge_ids -> NORMAL/BRANCH.
 *
 * @author Xander
 */
public final class RunStateAssembler {
    public static RunState toRunState(Iterable<WorkflowNodeRun> nodeRuns, String scopePathKey) {
        Map<String, NodeFact> facts = new LinkedHashMap<>();
        for (WorkflowNodeRun nodeRun : nodeRuns) {
            if (!Objects.equals(scopePathKey, nodeRun.scopePathKey)) {
                continue;   // only facts at this scope level feed this fold
            }
            facts.put(nodeRun.nodeId, toFact(nodeRun));
        }
        return new RunState(facts);
    }

    public static NodeFact toFact(WorkflowNodeRun nodeRun) {
        return switch (nodeRun.status) {
            case RUNNING, WAITING -> NodeFact.running(nodeRun.nodeId);   // in-flight / paused: out-edges stay PENDING
            case COMPLETED -> nodeRun.chosenEdgeIds != null
                ? NodeFact.completedBranch(nodeRun.nodeId, Set.copyOf(nodeRun.chosenEdgeIds))
                : NodeFact.completedNormal(nodeRun.nodeId);
            case SKIPPED -> NodeFact.skipped(nodeRun.nodeId);
            case FAILED_RETRYABLE -> NodeFact.failed(nodeRun.nodeId);
        };
    }

    private RunStateAssembler() {
    }
}
