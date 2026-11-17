package ai.core.tool.async;

import ai.core.tool.ToolCallResult;
import ai.core.tool.wait.TaskCompletionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int MAX_RETAINED_TASKS = 200;
    private static final AsyncToolTaskExecutor INSTANCE = new AsyncToolTaskExecutor();

    public static AsyncToolTaskExecutor getInstance() {
        return INSTANCE;
    }

    private static ToolCallResult toResult(String taskId, Object result, Throwable error) {
        if (error != null) {
            if (error instanceof java.util.concurrent.CancellationException) {
                return ToolCallResult.failed("Task cancelled: " + taskId).withStats("taskStatus", "cancelled");
            }
            LOGGER.error("Task failed: taskId={}, error={}", taskId, error.getMessage(), error);
            return ToolCallResult.failed("Task execution failed: " + error.getMessage());
        }
        if (result instanceof String s) {
            return ToolCallResult.completed(s);
        } else if (result instanceof ToolCallResult r) {
            return r;
        } else {
            return ToolCallResult.completed(result != null ? result.toString() : "");
        }
    }

    private final ExecutorService executor;
    private final Map<String, TaskState<?>> tasks = new ConcurrentHashMap<>();
    private final TaskCompletionRegistry completions = new TaskCompletionRegistry("executor", null);

    private AsyncToolTaskExecutor() {
        var factory = Thread.ofVirtual().name("async-tool-", 0).factory();
        this.executor = Executors.newThreadPerTaskExecutor(factory);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public <T> String submit(String taskIdPrefix, String toolName, Callable<T> task) {
        var taskId = taskIdPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        trimTasks();
        // Registered before submission so a completion on the worker thread can never race the entry.
        var completion = completions.register(taskId, null);
        var state = new TaskState<>(toolName, System.currentTimeMillis(), completion);
        tasks.put(taskId, state);
        try {
            state.future = executor.submit(() -> runTask(taskId, toolName, task, completion));
        } catch (RuntimeException e) {
            completion.complete(ToolCallResult.failed("Task could not be started: " + e.getMessage()));
            throw e;
        }
        LOGGER.debug("Submitted async task: taskId={}, tool={}", taskId, toolName);
        return taskId;
    }

    public ToolCallResult poll(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) {
            return ToolCallResult.failed("Task not found: " + taskId);
        }
        var snapshot = state.completion.peek();
        if (snapshot != null) return snapshot;

        var future = state.future;
        if (future == null || !future.isDone()) {
            return ToolCallResult.pending(taskId, "Task is still running");
        }
        try {
            // whenComplete normally settled it already; this covers the instant between done and callback
            state.completion.complete(toResult(taskId, future.get(), null));
        } catch (Exception e) {
            state.completion.complete(toResult(taskId, null, e));
        }
        return state.completion.peek();
    }

    public ToolCallResult cancel(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) {
            return ToolCallResult.failed("Task not found: " + taskId);
        }
        if (state.future != null) state.future.cancel(true);
        state.completion.complete(ToolCallResult.failed("Task cancelled: " + taskId)
                .withStats("taskStatus", "cancelled"));
        LOGGER.debug("Task cancelled: taskId={}, tool={}", taskId, state.toolName);
        return ToolCallResult.completed("Task cancelled: " + taskId);
    }

    public Optional<TaskCompletionRegistry.Entry> findCompletion(String taskId, String ownerScope) {
        return completions.find(taskId, ownerScope);
    }

    /** The issuing session is bound once the tool call has returned the task id to the agent. */
    public void bindOwner(String taskId, String ownerScope) {
        completions.find(taskId).ifPresent(entry -> entry.bindOwner(ownerScope));
    }

    public Optional<TaskInfo> getTaskInfo(String taskId) {
        var state = tasks.get(taskId);
        if (state == null) {
            return Optional.empty();
        }
        var future = state.future;
        return Optional.of(new TaskInfo(
                taskId,
                state.toolName,
                state.completion.isDone() || future != null && future.isDone(),
                state.startTimeMs,
                System.currentTimeMillis() - state.startTimeMs
        ));
    }

    public int getActiveTaskCount() {
        return (int) tasks.values().stream().filter(s -> !s.completion.isDone()).count();
    }

    private <T> T runTask(String taskId, String toolName, Callable<T> task,
                          TaskCompletionRegistry.Entry completion) throws Exception {
        try {
            var result = executeWithLogging(taskId, toolName, task);
            completion.complete(toResult(taskId, result, null));
            return result;
        } catch (Throwable e) {
            completion.complete(toResult(taskId, null, e));
            throw e;
        } finally {
            trimTasks();
        }
    }

    private <T> T executeWithLogging(String taskId, String toolName, Callable<T> task) throws Exception {
        LOGGER.debug("Starting async task execution: taskId={}, tool={}", taskId, toolName);
        var startTime = System.currentTimeMillis();
        try {
            return task.call();
        } finally {
            LOGGER.debug("Async task execution finished: taskId={}, tool={}, durationMs={}", taskId, toolName, System.currentTimeMillis() - startTime);
        }
    }

    private void trimTasks() {
        if (tasks.size() <= MAX_RETAINED_TASKS) return;
        var finished = new ArrayList<>(tasks.entrySet());
        finished.sort(Comparator.comparingLong(entry -> entry.getValue().completion.completedAtMs()));
        var excess = tasks.size() - MAX_RETAINED_TASKS;
        for (var entry : finished) {
            if (excess <= 0) break;
            if (!entry.getValue().completion.isDone()) continue;
            if (tasks.remove(entry.getKey(), entry.getValue())) excess--;
        }
    }

    private static final class TaskState<T> {
        volatile Future<T> future;
        final String toolName;
        final long startTimeMs;
        final TaskCompletionRegistry.Entry completion;

        private TaskState(String toolName, long startTimeMs, TaskCompletionRegistry.Entry completion) {
            this.toolName = toolName;
            this.startTimeMs = startTimeMs;
            this.completion = completion;
        }
    }

    public record TaskInfo(String taskId, String toolName, boolean isDone, long startTimeMs, long elapsedMs) {
    }
}
