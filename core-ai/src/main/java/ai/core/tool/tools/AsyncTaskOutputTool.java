package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.tool.wait.RuntimeWaitTool;
import ai.core.tool.wait.TaskAwaitCoordinator;
import ai.core.tool.wait.WaitRequest;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AsyncTaskOutputTool extends ToolCall implements RuntimeWaitTool {
    public static final String TOOL_NAME = "async_task_output";

    private static final String TOOL_DESC = """
            Query the status and output of async tasks: long-running tool calls that returned a task id
            (renders, assembly, video generation, async scripts) and background agents or commands launched
            with run_in_background=true.

            Use action='wait' with task_id when your next step depends on the result: the turn pauses
            without calling the model and resumes with the result as this call's output once the task
            reaches a terminal state. It does not return an interim PENDING and does not start a second
            reasoning turn. Waiting consumes the result and suppresses the automatic completion
            notification. Pass timeout_ms only when you want a bounded wait; when it expires you get
            WAIT_TIMEOUT and the task keeps running. Use action='poll' for an interim status; use
            action='cancel' to stop a running task or background agent.

            Statuses:
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
                case "wait" -> waitTask(taskId, timeout(args), context);
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
    public WaitRequest prepareWait(Map<String, Object> args) {
        if (!"wait".equals(getStringValue(args, "action"))) return null;
        var taskId = getStringValue(args, "task_id");
        if (taskId == null || taskId.isBlank()) return null;
        return WaitRequest.of(taskId, timeout(args));
    }

    private ToolCallResult waitTask(String taskId, long timeoutMs, ExecutionContext context) {
        if (context == null) {
            return ToolCallResult.failed("WAIT_UNSUPPORTED: waiting requires an agent execution context; "
                    + "use action='poll' instead");
        }
        return TaskAwaitCoordinator.getInstance()
                .await(WaitRequest.of(taskId, timeoutMs), null, context)
                .withToolName(TOOL_NAME);
    }

    private ToolCallResult pollTask(AsyncToolTaskExecutor executor, String taskId, ExecutionContext context) {
        var taskManager = context != null ? context.getTaskManager() : null;
        if (taskManager != null && taskManager.hasTask(taskId)) {
            var result = taskManager.pollTask(taskId);
            if (result.isTerminal()) {
                // Consume only the generation the poll answered for; a reused id still owes its own note.
                taskManager.findCompletion(taskId)
                        .ifPresentOrElse(entry -> taskManager.markConsumed(entry.ref()),
                                () -> taskManager.markConsumed(taskId));
            }
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

    private ToolCallResult cancelTask(AsyncToolTaskExecutor executor, String taskId, ExecutionContext context) {
        var taskManager = context != null ? context.getTaskManager() : null;
        if (taskManager != null && taskManager.hasTask(taskId)) return taskManager.cancelTask(taskId);
        var manager = context != null ? context.getAsyncTaskManager() : null;
        if (manager != null && manager.loadTask(taskId).isPresent()) return manager.cancelTask(taskId);
        return executor.cancel(taskId);
    }

    /**
     * Absent means no independent wait deadline; a negative or non-numeric value is reported by the
     * coordinator as an invalid request instead of being silently clamped.
     */
    private long timeout(Map<String, Object> args) {
        var value = args.get("timeout_ms");
        if (value == null) return 0;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
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
                            "For action='wait': optional bound in milliseconds; when it expires the call returns "
                                    + "WAIT_TIMEOUT and the task keeps running. Omit to wait until the task is terminal.")
                            .optional()
            ));
            var tool = new AsyncTaskOutputTool();
            build(tool);
            return tool;
        }
    }
}
