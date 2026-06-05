package ai.core.server.workflow.engine;

/**
 * Derived liveness of an edge during a run. Never persisted as independent state: the planner computes it
 * from the source node's fact on every pass (PENDING until the source is terminal).
 *
 * @author Xander
 */
public enum EdgeVerdict {
    PENDING,
    ACTIVE,
    SKIPPED
}
