package ai.core.tool.async;

import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author stephen
 */
public final class AsyncToolTaskExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncToolTaskExecutor.class);
    private static final AsyncToolTaskExecutor INSTANCE = new AsyncToolTaskExecutor();

    public static AsyncToolTaskExecutor getInstance() {
        return INSTANCE;
    }

    private final ExecutorService executor;
    private final Map<String, TaskState<?>> tasks = new ConcurrentHashMap<>();

    private AsyncToolTaskExecutor() {
        var factory = Thread.ofVirtual().name("async-tool-", 0).factory();
        this.executor = Executors.newThreadPerTaskExecutor(factory);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public <T> String submit(String taskIdPrefix, String toolName, Callable<T> task) {
        var taskId = taskIdPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);

        var future = executor.submit(() -> executeWithLogging(taskId, toolName, task));

        tasks.put(taskId, new TaskState<>(future, toolName, System.currentTimeMillis()));
        LOGGER.info("Submitted async task: taskId={}, tool={}", taskId, toolName);
        return taskId;
    }

    public ToolCallResult poll(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) {
            return ToolCallResult.failed("Task not found: " + taskId);
        }

        if (!state.future.isDone()) {
            return ToolCallResult.pending(taskId, "Task is still running");
        }

        try {
            var result = state.future.get();
            tasks.remove(taskId);
            LOGGER.info("Task completed: taskId={}, tool={}, durationMs={}", taskId, state.toolName, System.currentTimeMillis() - state.startTimeMs);

            if (result instanceof String s) {
                return ToolCallResult.completed(s);
            } else if (result instanceof ToolCallResult r) {
                return r;
            } else {
                return ToolCallResult.completed(result != null ? result.toString() : "");
            }
        } catch (Exception e) {
            tasks.remove(taskId);
            LOGGER.error("Task failed: taskId={}, error={}", taskId, e.getMessage(), e);
            return ToolCallResult.failed("Task execution failed: " + e.getMessage());
        }
    }

    public ToolCallResult cancel(String taskId) {
        var state = tasks.remove(taskId);
        if (state == null) {
            return ToolCallResult.failed("Task not found: " + taskId);
        }

        state.future.cancel(true);
        LOGGER.info("Task cancelled: taskId={}, tool={}", taskId, state.toolName);
        return ToolCallResult.completed("Task cancelled: " + taskId);
    }

    public Optional<TaskInfo> getTaskInfo(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(new TaskInfo(
                taskId,
                state.toolName,
                state.future.isDone(),
                state.startTimeMs,
                System.currentTimeMillis() - state.startTimeMs
        ));
    }

    public int getActiveTaskCount() {
        return (int) tasks.values().stream().filter(s -> !s.future.isDone()).count();
    }

    private <T> T executeWithLogging(String taskId, String toolName, Callable<T> task) throws Exception {
        LOGGER.info("Starting async task execution: taskId={}, tool={}", taskId, toolName);
        var startTime = System.currentTimeMillis();
        try {
            return task.call();
        } finally {
            LOGGER.info("Async task execution finished: taskId={}, tool={}, durationMs={}", taskId, toolName, System.currentTimeMillis() - startTime);
        }
    }

    private record TaskState<T>(Future<T> future, String toolName, long startTimeMs) {
    }

    public record TaskInfo(String taskId, String toolName, boolean isDone, long startTimeMs, long elapsedMs) {
    }
}
