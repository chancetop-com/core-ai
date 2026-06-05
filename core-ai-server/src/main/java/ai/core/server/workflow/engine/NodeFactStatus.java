package ai.core.server.workflow.engine;

/**
 * The planner's view of a node-run. This is the framework-free projection the pure planner folds; the
 * persisted Mongo enum (with RETRYABLE vs terminal FAILED nuance for recovery) is a separate concern.
 * Absence of a fact means "not started yet".
 *
 * @author Xander
 */
public enum NodeFactStatus {
    RUNNING,
    COMPLETED,
    SKIPPED,
    /** Union of the persisted FAILED and FAILED_RETRYABLE: a non-terminal "pause" that keeps out-edges PENDING (halt and wait), never a skip. */
    FAILED
}
