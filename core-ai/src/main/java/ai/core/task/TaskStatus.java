package ai.core.task;

/**
 * @author stephen
 */
public enum TaskStatus {
    INITED,
    WAIT_FOR_SUBTASK,
    COMPLETED,
    FAILED,
    CANCELLED
}
