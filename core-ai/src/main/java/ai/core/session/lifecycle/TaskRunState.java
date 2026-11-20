package ai.core.session.lifecycle;

/**
 * @author stephen
 */
public enum TaskRunState {
    QUEUED,
    REGISTERED,
    RUNNING,
    DRAINING,
    AWAITING_ASYNC,
    NEEDS_ATTENTION,
    TERMINAL
}
