package ai.core.tool;

import ai.core.llm.domain.FunctionCall;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.tools.AsyncTaskOutputTool;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of long-running tool calls: every {@code ToolCallResult.pending} a tool returns is stored
 * here by the executor (keyed by task id, tagged with the session that issued it), refreshed through
 * the tool's own {@code poll}, and kept after it turns terminal so a late poll still gets the answer.
 * Terminal transitions are announced to listeners — the server uses that to notify the owning session,
 * so an agent never has to sleep-poll a render or an assembly.
 *
 * @author stephen
 */
public class ToolCallAsyncTaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallAsyncTaskManager.class);
    private static final String TASK_PREFIX = "async_task:";

    private final PersistenceProvider persistenceProvider;
    private final Map<String, ToolCall> toolRegistry = new HashMap<>();
    private final List<TerminalListener> listeners = new CopyOnWriteArrayList<>();

    public ToolCallAsyncTaskManager(PersistenceProvider persistenceProvider) {
        this.persistenceProvider = persistenceProvider;
    }

    public void registerTool(ToolCall tool) {
        toolRegistry.put(tool.getName(), tool);
    }

    public void registerTools(Iterable<ToolCall> tools) {
        for (var tool : tools) {
            registerTool(tool);
        }
    }

    public void addTerminalListener(TerminalListener listener) {
        listeners.add(listener);
    }

    public void storeTask(ToolCallAsyncTask task) {
        storeTask(task, null);
    }

    /** sessionId is the conversation that issued the call; terminal notifications are addressed to it. */
    public void storeTask(ToolCallAsyncTask task, String sessionId) {
        var data = AsyncTaskData.from(task);
        data.sessionId = sessionId;
        save(data);
    }

    public Optional<ToolCallAsyncTask> loadTask(String taskId) {
        return loadData(taskId).flatMap(data -> {
            var tool = resolveTool(data.toolName);
            if (tool == null) {
                LOGGER.warn("Tool not found in registry: {}", data.toolName);
                return Optional.empty();
            }
            return Optional.of(data.toTask(tool));
        });
    }

    public Optional<String> sessionIdOf(String taskId) {
        return loadData(taskId).map(data -> data.sessionId);
    }

    public void deleteTask(String taskId) {
        persistenceProvider.delete(List.of(TASK_PREFIX + taskId));
        LOGGER.debug("Deleted async task: {}", taskId);
    }

    /** Task ids still pending or waiting for input — what a server-side driver refreshes each tick. */
    public List<String> listOpenTaskIds() {
        return listTasks().stream().filter(TaskSnapshot::open).map(TaskSnapshot::taskId).toList();
    }

    /**
     * One pass over the store, giving a driver everything it needs to decide what to poll and what to
     * re-announce. Kept as a single scan because both questions are asked on the same tick.
     */
    public List<TaskSnapshot> listTasks() {
        var snapshots = new ArrayList<TaskSnapshot>();
        for (var key : persistenceProvider.listIds(TASK_PREFIX)) {
            var json = persistenceProvider.load(key);
            if (json.isEmpty()) continue;
            var data = JSON.fromJSON(AsyncTaskData.class, json.get());
            snapshots.add(new TaskSnapshot(data.taskId, data.sessionId, data.status, isOpen(data),
                isNotificationPending(data), data.notifyAttempts, data.lastNotifyAttemptAtMs));
        }
        return snapshots;
    }

    private static boolean isOpen(AsyncTaskData data) {
        return data.status == ToolCallResult.Status.PENDING || data.status == ToolCallResult.Status.WAITING_FOR_INPUT;
    }

    private static boolean isTerminal(AsyncTaskData data) {
        return data.status == ToolCallResult.Status.COMPLETED || data.status == ToolCallResult.Status.FAILED;
    }

    /** Terminal, addressed to a session, and no one has confirmed that the session took it. */
    private static boolean isNotificationPending(AsyncTaskData data) {
        return isTerminal(data) && data.sessionId != null && data.notifiedAtMs == null;
    }

    /** Terminal tasks are kept for late readers; this drops the ones nobody will ask about any more. */
    public int purgeTerminalOlderThan(Duration age) {
        var cutoff = Instant.now().minus(age).toEpochMilli();
        var stale = new ArrayList<String>();
        for (var key : persistenceProvider.listIds(TASK_PREFIX)) {
            var json = persistenceProvider.load(key);
            if (json.isEmpty()) continue;
            var data = JSON.fromJSON(AsyncTaskData.class, json.get());
            var terminal = isTerminal(data);
            var lastTouch = data.lastPolledAtMs != null ? data.lastPolledAtMs : data.createdAtMs;
            if (terminal && lastTouch != null && lastTouch <= cutoff) stale.add(key);
        }
        if (!stale.isEmpty()) persistenceProvider.delete(stale);
        return stale.size();
    }

    /**
     * Refreshes one task through its tool. A terminal answer is stored (not deleted) and announced once;
     * a poll error keeps the task alive — the upstream may just be slow, and dropping the task would
     * orphan work that is still running.
     */
    public ToolCallResult pollTask(String taskId) {
        var dataOpt = loadData(taskId);
        if (dataOpt.isEmpty()) return ToolCallResult.failed("Task not found: " + taskId);
        var data = dataOpt.get();
        if (data.status == ToolCallResult.Status.COMPLETED || data.status == ToolCallResult.Status.FAILED) return data.restoredResult();
        var tool = resolveTool(data.toolName);
        if (tool == null) return ToolCallResult.failed("Tool not available for task " + taskId + ": " + data.toolName);
        if (tool instanceof AsyncTaskOutputTool) {
            // Corrupt record from before the executor stopped re-registering poll relays: the stored tool is the
            // polling tool itself, which never started the task and cannot poll it, so it can never turn terminal.
            // Drop it (the underlying work keeps running and is reachable through async_task_output by task id).
            LOGGER.warn("Dropping async task {}: stored tool '{}' does not support polling", taskId, data.toolName);
            deleteTask(taskId);
            return ToolCallResult.failed("Dropped task " + taskId + ": tool '" + data.toolName + "' does not support polling");
        }
        var task = data.toTask(tool);
        if (!task.isPending()) return ToolCallResult.failed("Task is not pending: " + taskId);
        try {
            var result = tool.poll(taskId);
            result.withToolName(tool.getName());
            LOGGER.debug("Polled task {}: status={}", taskId, result.getStatus());
            var polled = task.withPolled(result);
            var updated = AsyncTaskData.from(polled);
            updated.inheritDelivery(data);
            if (!result.isTerminal()) {
                save(updated);
                return result;
            }
            if (updated.sessionId == null) {
                // no conversation to continue: settle the delivery state now so the retry sweep skips it
                updated.notifiedAtMs = System.currentTimeMillis();
                save(updated);
                return result;
            }
            announce(updated, polled, result);
            return result;
        } catch (Exception e) {
            LOGGER.warn("Error polling task {}: {}", taskId, e.getMessage(), e);
            return ToolCallResult.failed("Poll error: " + e.getMessage());
        }
    }

    public ToolCallResult submitInput(String taskId, String input) {
        var taskOpt = loadTask(taskId);
        if (taskOpt.isEmpty()) return ToolCallResult.failed("Task not found: " + taskId);
        var task = taskOpt.get();
        if (!task.isWaitingForInput()) return ToolCallResult.failed("Task is not waiting for input: " + taskId);
        try {
            var result = task.tool().submitInput(taskId, input);
            result.withToolName(task.tool().getName());
            if (result.isTerminal()) deleteTask(taskId);
            return result;
        } catch (Exception e) {
            LOGGER.error("Error submitting input for task {}: {}", taskId, e.getMessage(), e);
            deleteTask(taskId);
            return ToolCallResult.failed("Submit input error: " + e.getMessage());
        }
    }

    public ToolCallResult cancelTask(String taskId) {
        var taskOpt = loadTask(taskId);
        if (taskOpt.isEmpty()) return ToolCallResult.failed("Task not found: " + taskId);
        var task = taskOpt.get();
        try {
            var result = task.tool().cancel(taskId);
            deleteTask(taskId);
            return result;
        } catch (UnsupportedOperationException e) {
            return ToolCallResult.failed("Tool does not support cancellation");
        } catch (Exception e) {
            LOGGER.error("Error cancelling task {}: {}", taskId, e.getMessage(), e);
            deleteTask(taskId);
            return ToolCallResult.failed("Cancel error: " + e.getMessage());
        }
    }

    /** Registry lookup by tool name; hosts with a wider tool catalogue override this to fall back to it. */
    protected ToolCall resolveTool(String toolName) {
        return toolRegistry.get(toolName);
    }

    /**
     * Re-announces a terminal task whose session never confirmed the notification, so a completion lost
     * to a dead session, a failed rebuild or a Redis hiccup is not lost for good — the task is already
     * terminal, so nothing would ever poll, let alone announce, it again.
     */
    public boolean retryNotification(String taskId) {
        var dataOpt = loadData(taskId);
        if (dataOpt.isEmpty()) return false;
        var data = dataOpt.get();
        if (!isNotificationPending(data)) return false;
        var tool = resolveTool(data.toolName);
        if (tool == null) {
            LOGGER.warn("cannot re-announce async task {}: tool '{}' is not available", taskId, data.toolName);
            return false;
        }
        LOGGER.info("re-announcing async task notification, taskId={}, session={}, attempt={}", taskId, data.sessionId, data.notifyAttempts + 1);
        announce(data, data.toTask(tool), data.restoredResult());
        return true;
    }

    /**
     * Claims delivery of a terminal task's notification. Exactly one caller wins, so a task announced
     * twice (two replicas polled it on the same tick) is still injected into its session once. The
     * winner calls {@link #reopenNotification} when the injection then fails.
     */
    public synchronized boolean markNotificationDelivered(String taskId) {
        var dataOpt = loadData(taskId);
        if (dataOpt.isEmpty()) return false;
        var data = dataOpt.get();
        if (data.notifiedAtMs != null) return false;
        data.notifiedAtMs = System.currentTimeMillis();
        save(data);
        return true;
    }

    /** Hands a claimed notification back to the retry sweep after the session refused it. */
    public synchronized void reopenNotification(String taskId) {
        var dataOpt = loadData(taskId);
        if (dataOpt.isEmpty()) return;
        var data = dataOpt.get();
        if (data.notifiedAtMs == null) return;
        data.notifiedAtMs = null;
        save(data);
    }

    /** Stops re-announcing a task no one can take: its session is gone, or the retries ran out. */
    public void abandonNotification(String taskId) {
        if (markNotificationDelivered(taskId)) {
            LOGGER.warn("giving up on async task notification, taskId={}", taskId);
        }
    }

    /**
     * Hands a terminal task to its listeners and records the attempt. A listener returning normally is
     * not a delivery receipt: the session side confirms with {@link #markNotificationDelivered}, and the
     * attempt counter written here is what lets a driver back off and eventually give up.
     */
    private void announce(AsyncTaskData data, ToolCallAsyncTask task, ToolCallResult result) {
        data.notifyAttempts++;
        data.lastNotifyAttemptAtMs = System.currentTimeMillis();
        save(data);
        for (var listener : listeners) {
            try {
                listener.onTerminal(data.sessionId, task, result);
            } catch (Exception e) {
                LOGGER.warn("async task listener failed, taskId={}", task.taskId(), e);
            }
        }
    }

    private Optional<AsyncTaskData> loadData(String taskId) {
        return persistenceProvider.load(TASK_PREFIX + taskId).map(json -> JSON.fromJSON(AsyncTaskData.class, json));
    }

    private void save(AsyncTaskData data) {
        persistenceProvider.save(TASK_PREFIX + data.taskId, JSON.toJSON(data));
        LOGGER.debug("Stored async task: {} status={}", data.taskId, data.status);
    }

    public interface TerminalListener {
        void onTerminal(String sessionId, ToolCallAsyncTask task, ToolCallResult result);
    }

    /** What one stored task looks like to a server-side driver, without materialising its tool. */
    public record TaskSnapshot(String taskId, String sessionId, ToolCallResult.Status status, boolean open,
                               boolean notificationPending, int notifyAttempts, Long lastNotifyAttemptAtMs) {
    }

    public static class AsyncTaskData {
        public static AsyncTaskData from(ToolCallAsyncTask task) {
            var data = new AsyncTaskData();
            data.taskId = task.taskId();
            data.toolName = task.tool().getName();
            data.originalCall = task.originalCall();
            data.status = task.status();
            data.createdAtMs = task.createdAt().toEpochMilli();
            data.lastPolledAtMs = task.lastPolledAt() != null ? task.lastPolledAt().toEpochMilli() : null;
            data.pollCount = task.pollCount();
            data.lastMessage = task.lastResult() != null ? task.lastResult().getResult() : null;
            return data;
        }

        public String taskId;
        public String toolName;
        public String sessionId;
        public FunctionCall originalCall;
        public ToolCallResult.Status status;
        public Long createdAtMs;
        public Long lastPolledAtMs;
        public int pollCount;
        /** text of the last result; ToolCallResult itself is not JSON-friendly, so status + text are what survive the store */
        public String lastMessage;
        /** set once the owning session has accepted the terminal notification; null means still owed */
        public Long notifiedAtMs;
        public int notifyAttempts;
        public Long lastNotifyAttemptAtMs;

        /** {@link #from} rebuilds the task facts only; who it belongs to and what it still owes carries over. */
        void inheritDelivery(AsyncTaskData previous) {
            sessionId = previous.sessionId;
            notifiedAtMs = previous.notifiedAtMs;
            notifyAttempts = previous.notifyAttempts;
            lastNotifyAttemptAtMs = previous.lastNotifyAttemptAtMs;
        }

        public ToolCallAsyncTask toTask(ToolCall tool) {
            return new ToolCallAsyncTask(
                taskId,
                tool,
                originalCall,
                status,
                Instant.ofEpochMilli(createdAtMs),
                lastPolledAtMs != null ? Instant.ofEpochMilli(lastPolledAtMs) : null,
                pollCount,
                restoredResult()
            );
        }

        ToolCallResult restoredResult() {
            var message = lastMessage != null ? lastMessage : "";
            if (status == null) return ToolCallResult.pending(taskId, message);
            return switch (status) {
                case COMPLETED -> ToolCallResult.completed(message);
                case FAILED -> ToolCallResult.failed(message);
                case WAITING_FOR_INPUT -> ToolCallResult.waitingForInput(taskId, message);
                default -> ToolCallResult.pending(taskId, message);
            };
        }
    }
}
