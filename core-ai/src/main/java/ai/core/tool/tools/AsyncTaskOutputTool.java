package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AsyncTaskOutputTool extends ToolCall {
    public static final String TOOL_NAME = "async_task_output";

    private static final long DEFAULT_WAIT_TIMEOUT_MS = 30_000;
    private static final long MAX_WAIT_TIMEOUT_MS = 30 * 60 * 1000;
    private static final long WAIT_POLL_INTERVAL_MS = 1000;

    private static final String TOOL_DESC = """
            Query the status and output of async tasks: long-running tool calls that returned a task id
            (renders, assembly, video generation, async scripts) and background agents or commands launched
            with run_in_background=true.

            Use action='wait' with task_id to block until the task reaches a terminal state (or timeout_ms
            expires) and get its result in this turn. Waiting consumes the result and suppresses the automatic
            completion notification, so prefer it when your next step depends on the result. Use action='poll'
            only when you need an interim status; use action='cancel' to stop a running task or background agent.

            Poll and wait return one of these statuses:
            - PENDING: Task is still running, poll or wait again later
            - COMPLETED: Task finished successfully, result is included
            - FAILED: Task failed, error message is included
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            var action = getStringValue(args, "action");
            var taskId = getStringValue(args, "task_id");

            if (taskId == null || taskId.isBlank()) {
                return ToolCallResult.failed("Error: task_id is required.")
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            var executor = AsyncToolTaskExecutor.getInstance();
            var result = switch (action) {
                case "poll" -> pollTask(executor, taskId, context);
                case "wait" -> waitTask(executor, taskId, waitTimeout(args), context);
                case "cancel" -> cancelTask(executor, taskId, context);
                default -> ToolCallResult.failed("Unknown action: " + action + ". Use 'poll', 'wait' or 'cancel'.");
            };

            return result.withDuration(System.currentTimeMillis() - startTime)
                    .withStats("action", action)
                    .withStats("taskId", taskId);
        } catch (Exception e) {
            return ToolCallResult.failed("Error: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public long getTimeoutMs() {
        return MAX_WAIT_TIMEOUT_MS + 60_000;
    }

    private ToolCallResult pollTask(AsyncToolTaskExecutor executor, String taskId, ExecutionContext context) {
        var taskManager = context != null ? context.getTaskManager() : null;
        if (taskManager != null && taskManager.hasTask(taskId)) {
            var result = taskManager.pollTask(taskId);
            if (result.isTerminal()) taskManager.markConsumed(taskId);
            return result;
        }
        var manager = context != null ? context.getAsyncTaskManager() : null;
        if (manager != null && manager.loadTask(taskId).isPresent()) {
            var result = manager.pollTask(taskId);
            if (manager.isTaskTerminal(taskId)) manager.markNotificationDelivered(taskId);
            return result.withManagedTask();
        }
        var result = executor.poll(taskId);
        var taskInfo = executor.getTaskInfo(taskId);
        if (taskInfo.isPresent()) {
            var info = taskInfo.get();
            result.withStats("toolName", info.toolName())
                    .withStats("elapsedMs", info.elapsedMs());
        }
        return result;
    }

    private ToolCallResult waitTask(AsyncToolTaskExecutor executor, String taskId, long timeoutMs, ExecutionContext context) {
        var taskManager = context != null ? context.getTaskManager() : null;
        if (taskManager != null && taskManager.hasTask(taskId)) {
            var result = taskManager.awaitTask(taskId, timeoutMs);
            if (result.isTerminal()) taskManager.markConsumed(taskId);
            return result;
        }
        var manager = context != null ? context.getAsyncTaskManager() : null;
        if (manager != null && manager.loadTask(taskId).isPresent()) {
            return waitForServerTask(manager, taskId, timeoutMs, context);
        }
        return waitForExecutorTask(executor, taskId, timeoutMs, context);
    }

    private ToolCallResult waitForServerTask(ToolCallAsyncTaskManager manager, String taskId, long timeoutMs,
                                             ExecutionContext context) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            var result = manager.pollTask(taskId);
            if (manager.isTaskTerminal(taskId)) {
                manager.markNotificationDelivered(taskId);
                return result.withManagedTask();
            }
            if (result.isFailed()) return result.withManagedTask();
            var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return ToolCallResult.pending(taskId, "Task is still running after " + timeoutMs + "ms").withManagedTask();
            }
            if (isCancelled(context)) return ToolCallResult.failed("Task wait cancelled: " + taskId).withManagedTask();
            if (!sleep(Math.min(WAIT_POLL_INTERVAL_MS, remaining))) {
                return ToolCallResult.failed("Task wait interrupted: " + taskId).withManagedTask();
            }
        }
    }

    private ToolCallResult waitForExecutorTask(AsyncToolTaskExecutor executor, String taskId, long timeoutMs,
                                               ExecutionContext context) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            var result = executor.poll(taskId);
            if (result.isTerminal()) return result;
            if (result.isFailed()) return result;
            var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return ToolCallResult.pending(taskId, "Task is still running after " + timeoutMs + "ms");
            }
            if (isCancelled(context)) return ToolCallResult.failed("Task wait cancelled: " + taskId);
            if (!sleep(Math.min(WAIT_POLL_INTERVAL_MS, remaining))) {
                return ToolCallResult.failed("Task wait interrupted: " + taskId);
            }
        }
    }

    private ToolCallResult cancelTask(AsyncToolTaskExecutor executor, String taskId, ExecutionContext context) {
        var taskManager = context != null ? context.getTaskManager() : null;
        if (taskManager != null && taskManager.hasTask(taskId)) return taskManager.cancelTask(taskId);
        var manager = context != null ? context.getAsyncTaskManager() : null;
        if (manager != null && manager.loadTask(taskId).isPresent()) return manager.cancelTask(taskId);
        return executor.cancel(taskId);
    }

    private boolean isCancelled(ExecutionContext context) {
        return context != null && context.isCancelled();
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long waitTimeout(Map<String, Object> args) {
        var value = args.get("timeout_ms");
        var timeout = value instanceof Number number ? number.longValue() : DEFAULT_WAIT_TIMEOUT_MS;
        return Math.max(1000, Math.min(MAX_WAIT_TIMEOUT_MS, timeout));
    }

    public static class Builder extends ToolCall.Builder<Builder, AsyncTaskOutputTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public AsyncTaskOutputTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "action", "The action to perform: 'poll', 'wait' or 'cancel'")
                            .required().enums(List.of("poll", "wait", "cancel")),
                    ToolCallParameters.ParamSpec.of(String.class, "task_id", "The task ID to poll, wait for or cancel").required(),
                    ToolCallParameters.ParamSpec.of(Integer.class, "timeout_ms",
                            "For action='wait': how long to wait before returning PENDING, in milliseconds (default 30000, max 1800000)")
                            .optional()
            ));
            var tool = new AsyncTaskOutputTool();
            build(tool);
            return tool;
        }
    }
}
