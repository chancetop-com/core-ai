package ai.core.tool;

import ai.core.tool.wait.TaskCompletionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Poll-based completion bridge for managed async tasks (sandbox tasks and external async tools).
 * Tracks in-process {@link TaskCompletionRegistry} entries and runs a virtual-thread poll loop only
 * while a waiter is actively parked on an open task.
 *
 * @author stephen
 */
final class ManagedTaskCompletionBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedTaskCompletionBridge.class);

    private final TaskCompletionRegistry completions = new TaskCompletionRegistry("managed", null);
    private final Set<String> activePollers = ConcurrentHashMap.newKeySet();

    void register(String taskId, String sessionId) {
        completions.register(taskId, sessionId);
    }

    void complete(String taskId, ToolCallResult result) {
        completions.find(taskId).ifPresent(entry -> entry.complete(result));
    }

    boolean isNotificationSuppressed(String taskId) {
        var entry = completions.find(taskId).orElse(null);
        return entry != null && entry.isNotificationSuppressed();
    }

    Optional<TaskCompletionRegistry.Entry> findOrEnsure(AsyncTaskData data, boolean terminal, String ownerScope) {
        if (data.sessionId != null && ownerScope != null && !data.sessionId.equals(ownerScope)) {
            return Optional.empty();
        }
        var entry = completions.find(data.taskId, ownerScope).orElse(null);
        if (entry == null) {
            entry = completions.register(data.taskId, data.sessionId);
        }
        if (terminal && !entry.isDone()) {
            entry.complete(data.restoredResult().withToolName(data.toolName));
        }
        return Optional.of(entry);
    }

    void startPolling(String taskId, TaskCompletionRegistry.Entry entry, Runnable pollAction, LongSupplier intervalMs) {
        if (entry.isDone() || !activePollers.add(taskId)) return;
        Thread.ofVirtual().name("managed-wait-poll-" + taskId).start(() -> pollLoop(taskId, entry, pollAction, intervalMs));
    }

    private void pollLoop(String taskId, TaskCompletionRegistry.Entry entry, Runnable pollAction, LongSupplier intervalMs) {
        try {
            while (!entry.isDone() && entry.hasWaiters()) {
                pollOnce(taskId, pollAction);
                if (entry.isDone() || !entry.hasWaiters()) break;
                Thread.sleep(Math.max(50L, intervalMs.getAsLong()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activePollers.remove(taskId);
        }
    }

    private void pollOnce(String taskId, Runnable pollAction) {
        try {
            pollAction.run();
        } catch (RuntimeException e) {
            LOGGER.warn("managed wait poll failed, taskId={}", taskId, e);
        }
    }
}
