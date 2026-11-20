package ai.core.session.lifecycle;

/**
 * @author stephen
 */
public enum TaskRunState {
    REGISTERED,
    RUNNING,
    DRAINING,
    AWAITING_ASYNC,
    NEEDS_ATTENTION,
    TERMINAL
}
