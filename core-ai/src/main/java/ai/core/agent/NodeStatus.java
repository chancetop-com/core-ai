package ai.core.agent;

/**
 * @author stephen
 */
public enum NodeStatus {
    INITED,
    RUNNING,
    WAITING_FOR_USER_INPUT,
    WAITING_FOR_ASYNC_TASK,
    COMPLETED,
    FAILED
}
