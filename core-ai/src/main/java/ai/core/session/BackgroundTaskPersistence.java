package ai.core.session;

import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Safe bridge between {@link BackgroundTaskManager} and an optional {@link BackgroundTaskStore}.
 * Storage faults are logged and ignored so persistence never breaks in-process task execution, and
 * open rows belonging to a dead pod are transitioned to LOST on first poll without duplicate alerts.
 *
 * @author stephen
 */
final class BackgroundTaskPersistence {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundTaskPersistence.class);
    static final String LOST_MESSAGE = "SERVER_TASK_LOST: server restarted while background task was running";

    private final BackgroundTaskStore store;

    BackgroundTaskPersistence(BackgroundTaskStore store) {
        this.store = store;
    }

    void recordRunning(String taskId, String sessionId, String outputRef) {
        if (store == null) return;
        try {
            store.saveRunning(taskId, sessionId, outputRef);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to persist running background task, taskId={}", taskId, e);
        }
    }

    void recordTerminal(BackgroundTaskStore.StoredTask task) {
        if (store == null) return;
        try {
            store.saveTerminal(task);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to persist terminal background task, taskId={}", task.taskId(), e);
        }
    }

    boolean hasStored(String sessionId, String taskId) {
        return find(sessionId, taskId).isPresent();
    }

    Optional<ToolCallResult> pollStored(String sessionId, String taskId) {
        var found = find(sessionId, taskId);
        if (found.isEmpty()) return Optional.empty();
        var task = found.get();
        if (task.isOpen()) {
            markLost(task);
            return Optional.of(ToolCallResult.failed(LOST_MESSAGE).withStats("taskStatus", "lost"));
        }
        return Optional.of(toResult(task));
    }

    Optional<ToolCallResult> cancelStored(String sessionId, String taskId) {
        var found = find(sessionId, taskId);
        if (found.isEmpty()) return Optional.empty();
        var task = found.get();
        if (task.isOpen()) {
            markLost(task);
            return Optional.of(ToolCallResult.failed(LOST_MESSAGE).withStats("taskStatus", "lost"));
        }
        return Optional.of(ToolCallResult.failed("Task already finished: " + taskId));
    }

    private void markLost(BackgroundTaskStore.StoredTask task) {
        recordTerminal(new BackgroundTaskStore.StoredTask(
                task.taskId(), task.sessionId(), "lost", null, LOST_MESSAGE, task.outputRef(), true));
    }

    private Optional<BackgroundTaskStore.StoredTask> find(String sessionId, String taskId) {
        if (store == null) return Optional.empty();
        try {
            return store.find(sessionId, taskId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to load persisted background task, taskId={}", taskId, e);
            return Optional.empty();
        }
    }

    private ToolCallResult toResult(BackgroundTaskStore.StoredTask task) {
        if ("completed".equalsIgnoreCase(task.status())) {
            var body = task.result() != null ? task.result() : "";
            return ToolCallResult.completed(body).withStats("taskStatus", "completed");
        }
        if (task.error() != null && !task.error().isBlank()) {
            return ToolCallResult.failed(task.error()).withStats("taskStatus", task.status());
        }
        if (task.result() != null && !task.result().isBlank()) {
            return ToolCallResult.failed(task.result()).withStats("taskStatus", task.status());
        }
        return ToolCallResult.failed("Task " + task.status()).withStats("taskStatus", task.status());
    }
}
