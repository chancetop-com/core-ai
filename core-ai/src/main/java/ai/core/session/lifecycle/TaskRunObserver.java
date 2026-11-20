package ai.core.session.lifecycle;

/**
 * Implementations must be non-blocking: they are called on session driver and background task
 * threads, never inside a caller-held lock.
 *
 * @author stephen
 */
public interface TaskRunObserver {
    default void onTaskRunRegistered(TaskRunSnapshot snapshot) {
    }

    default void onTaskRunChanged(TaskRunSnapshot snapshot) {
    }

    default void onTaskRunEnded(TaskRunSnapshot snapshot, TaskRunEnded ended) {
    }
}
