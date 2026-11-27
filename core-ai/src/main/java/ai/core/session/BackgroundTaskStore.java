package ai.core.session;

import java.util.Optional;

/**
 * Durable store for server-side background tasks managed by {@link BackgroundTaskManager}.
 * Survives server restarts so open tasks from a dead pod can be surfaced as LOST instead of
 * vanishing, and finished tasks stay queryable by {@code async_task_output}.
 *
 * @author stephen
 */
public interface BackgroundTaskStore {
    void saveRunning(String taskId, String sessionId, String outputRef);

    void saveTerminal(StoredTask task);

    void markNotified(String taskId);

    Optional<StoredTask> find(String sessionId, String taskId);

    record StoredTask(String taskId, String sessionId, String status, String result, String error, String outputRef, boolean notified) {
        public boolean isOpen() {
            return "running".equalsIgnoreCase(status);
        }
    }
}
