package ai.core.tool;

import ai.core.llm.domain.FunctionCall;
import ai.core.persistence.PersistenceProvider;
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
        var ids = new ArrayList<String>();
        for (var key : persistenceProvider.listIds(TASK_PREFIX)) {
            var json = persistenceProvider.load(key);
            if (json.isEmpty()) continue;
            var data = JSON.fromJSON(AsyncTaskData.class, json.get());
            if (data.status == ToolCallResult.Status.PENDING || data.status == ToolCallResult.Status.WAITING_FOR_INPUT) ids.add(data.taskId);
        }
        return ids;
    }

    /** Terminal tasks are kept for late readers; this drops the ones nobody will ask about any more. */
    public int purgeTerminalOlderThan(Duration age) {
        var cutoff = Instant.now().minus(age).toEpochMilli();
        var stale = new ArrayList<String>();
        for (var key : persistenceProvider.listIds(TASK_PREFIX)) {
            var json = persistenceProvider.load(key);
            if (json.isEmpty()) continue;
            var data = JSON.fromJSON(AsyncTaskData.class, json.get());
            var terminal = data.status == ToolCallResult.Status.COMPLETED || data.status == ToolCallResult.Status.FAILED;
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
        var task = data.toTask(tool);
        if (!task.isPending()) return ToolCallResult.failed("Task is not pending: " + taskId);
        try {
            var result = tool.poll(taskId);
            result.withToolName(tool.getName());
            LOGGER.debug("Polled task {}: status={}", taskId, result.getStatus());
            var polled = task.withPolled(result);
            var updated = AsyncTaskData.from(polled);
            updated.sessionId = data.sessionId;
            save(updated);
            if (result.isTerminal()) announce(data.sessionId, polled, result);
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

    private void announce(String sessionId, ToolCallAsyncTask task, ToolCallResult result) {
        for (var listener : listeners) {
            try {
                listener.onTerminal(sessionId, task, result);
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
