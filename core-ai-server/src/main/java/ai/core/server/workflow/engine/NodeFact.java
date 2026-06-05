package ai.core.server.workflow.engine;

import java.util.Set;

/**
 * The durable fact about a node at one scope, projected from a persisted node-run. The planner folds a set
 * of these into the next frontier. {@code kind} and {@code chosenEdgeIds} only matter when COMPLETED.
 *
 * @author Xander
 */
public record NodeFact(String nodeId, NodeFactStatus status, OutcomeKind kind, Set<String> chosenEdgeIds) {
    public NodeFact {
        chosenEdgeIds = chosenEdgeIds == null ? Set.of() : Set.copyOf(chosenEdgeIds);
    }

    public static NodeFact running(String nodeId) {
        return new NodeFact(nodeId, NodeFactStatus.RUNNING, OutcomeKind.NORMAL, Set.of());
    }

    public static NodeFact completedNormal(String nodeId) {
        return new NodeFact(nodeId, NodeFactStatus.COMPLETED, OutcomeKind.NORMAL, Set.of());
    }

    public static NodeFact completedBranch(String nodeId, Set<String> chosenEdgeIds) {
        return new NodeFact(nodeId, NodeFactStatus.COMPLETED, OutcomeKind.BRANCH, chosenEdgeIds);
    }

    public static NodeFact skipped(String nodeId) {
        return new NodeFact(nodeId, NodeFactStatus.SKIPPED, OutcomeKind.NORMAL, Set.of());
    }

    public static NodeFact failed(String nodeId) {
        return new NodeFact(nodeId, NodeFactStatus.FAILED, OutcomeKind.NORMAL, Set.of());
    }
}
