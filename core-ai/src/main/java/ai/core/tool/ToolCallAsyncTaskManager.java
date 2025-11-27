package ai.core.tool;

import ai.core.llm.domain.FunctionCall;
import ai.core.persistence.PersistenceProvider;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class ToolCallAsyncTaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallAsyncTaskManager.class);
    private static final String TASK_PREFIX = "async_task:";

    private final PersistenceProvider persistenceProvider;
    private final Map<String, ToolCall> toolRegistry = new HashMap<>();

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

    public void storeTask(ToolCallAsyncTask task) {
        var data = AsyncTaskData.from(task);
        persistenceProvider.save(TASK_PREFIX + task.taskId(), JSON.toJSON(data));
        LOGGER.info("Stored async task: {}", task.taskId());
    }

    public Optional<ToolCallAsyncTask> loadTask(String taskId) {
        var json = persistenceProvider.load(TASK_PREFIX + taskId);
        if (json.isEmpty()) {
            return Optional.empty();
        }

        var data = JSON.fromJSON(AsyncTaskData.class, json.get());
        var tool = toolRegistry.get(data.toolName);
        if (tool == null) {
            LOGGER.warn("Tool not found in registry: {}", data.toolName);
            return Optional.empty();
        }

        return Optional.of(data.toTask(tool));
    }

    public void deleteTask(String taskId) {
        persistenceProvider.delete(java.util.List.of(TASK_PREFIX + taskId));
        LOGGER.info("Deleted async task: {}", taskId);
    }

    public ToolCallResult pollTask(String taskId) {
        var taskOpt = loadTask(taskId);
        if (taskOpt.isEmpty()) {
            return ToolCallResult.failed("Task not found: " + taskId);
        }

        var task = taskOpt.get();
        if (!task.isPending()) {
            return ToolCallResult.failed("Task is not pending: " + taskId);
        }

        try {
            var result = task.tool().poll(taskId);
            result.withToolName(task.tool().getName());
            LOGGER.info("Polled task {}: status={}", taskId, result.getStatus());

            if (result.isTerminal()) {
                deleteTask(taskId);
            } else {
                storeTask(task.withPolled(result));
            }

            return result;
        } catch (Exception e) {
            LOGGER.error("Error polling task {}: {}", taskId, e.getMessage(), e);
            deleteTask(taskId);
            return ToolCallResult.failed("Poll error: " + e.getMessage());
        }
    }

    public ToolCallResult submitInput(String taskId, String input) {
        var taskOpt = loadTask(taskId);
        if (taskOpt.isEmpty()) {
            return ToolCallResult.failed("Task not found: " + taskId);
        }

        var task = taskOpt.get();
        if (!task.isWaitingForInput()) {
            return ToolCallResult.failed("Task is not waiting for input: " + taskId);
        }

        try {
            var result = task.tool().submitInput(taskId, input);
            result.withToolName(task.tool().getName());

            if (result.isTerminal()) {
                deleteTask(taskId);
            }

            return result;
        } catch (Exception e) {
            LOGGER.error("Error submitting input for task {}: {}", taskId, e.getMessage(), e);
            deleteTask(taskId);
            return ToolCallResult.failed("Submit input error: " + e.getMessage());
        }
    }

    public ToolCallResult cancelTask(String taskId) {
        var taskOpt = loadTask(taskId);
        if (taskOpt.isEmpty()) {
            return ToolCallResult.failed("Task not found: " + taskId);
        }

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
            data.lastResult = task.lastResult();
            return data;
        }

        public String taskId;
        public String toolName;
        public FunctionCall originalCall;
        public ToolCallResult.Status status;
        public Long createdAtMs;
        public Long lastPolledAtMs;
        public int pollCount;
        public ToolCallResult lastResult;

        public ToolCallAsyncTask toTask(ToolCall tool) {
            return new ToolCallAsyncTask(
                taskId,
                tool,
                originalCall,
                status,
                java.time.Instant.ofEpochMilli(createdAtMs),
                lastPolledAtMs != null ? java.time.Instant.ofEpochMilli(lastPolledAtMs) : null,
                pollCount,
                lastResult
            );
        }
    }
}
