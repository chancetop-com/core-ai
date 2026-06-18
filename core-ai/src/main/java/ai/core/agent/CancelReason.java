package ai.core.agent;

/**
 * Semantic cancellation reason that replaces the ad-hoc {@code boolean cancelled} flag.
 * Each reason implies different behavior for connection close, process kill,
 * interruption marker injection, and persistence.
 *
 * @author lim
 */
public enum CancelReason {
    USER_CANCELLED,
    NEW_MESSAGE_INTERRUPT,
    REPLACED,
    TIMEOUT,
    SIBLING_ERROR,
    BUDGET_LIMITED
}
