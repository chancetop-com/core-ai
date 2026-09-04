package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;

import java.util.List;

/**
 * @author stephen
 */
public class AsyncTaskOutputTool extends ToolCall {
    public static final String TOOL_NAME = "async_task_output";

    private static final String TOOL_DESC = """
            Query the status and output of async tasks: long-running tool calls that returned a task id
            (renders, assembly, video generation, async scripts) and background agents launched via the
            task tool (run_in_background=true). You are notified automatically when such a task finishes,
            so poll only when you need an interim status.

            Use action='poll' with task_id to check a specific task's status and get its result when completed.
            Use action='cancel' with task_id to stop a running task or background agent.

            Poll returns one of these statuses:
            - PENDING: Task is still running, poll again later
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
                case "cancel" -> cancelTask(executor, taskId, context);
                default -> ToolCallResult.failed("Unknown action: " + action + ". Use 'poll' or 'cancel'.");
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

    private ToolCallResult pollTask(AsyncToolTaskExecutor executor, String taskId, ExecutionContext context) {
        // long-running tool calls (pending results) live in the async task manager, which drives the tool's own poll
        var manager = context != null ? context.getAsyncTaskManager() : null;
        if (manager != null && manager.loadTask(taskId).isPresent()) {
            // relay of a task the manager already tracks: marking it managed keeps the executor from
            // re-registering the pending result under this polling tool (which does not support poll)
            return manager.pollTask(taskId).withManagedTask();
        }
        var result = executor.poll(taskId);
        var taskManager = context != null ? context.getTaskManager() : null;
        if (taskManager != null && result.isFailed() && taskManager.isRunning(taskId)) {
            // Not an async tool task - a background agent launched via the task tool is still running.
            // The agent task manager owns it, so this status must never be registered as an async tool task.
            return ToolCallResult.pending(taskId, "Background agent is still running").withManagedTask();
        }

        var taskInfo = executor.getTaskInfo(taskId);
        if (taskInfo.isPresent()) {
            var info = taskInfo.get();
            result.withStats("toolName", info.toolName())
                    .withStats("elapsedMs", info.elapsedMs());
        }

        return result;
    }

    private ToolCallResult cancelTask(AsyncToolTaskExecutor executor, String taskId, ExecutionContext context) {
        var manager = context != null ? context.getAsyncTaskManager() : null;
        if (manager != null && manager.loadTask(taskId).isPresent()) return manager.cancelTask(taskId);
        var result = executor.cancel(taskId);
        var taskManager = context != null ? context.getTaskManager() : null;
        if (taskManager != null && result.isFailed() && taskManager.cancel(taskId)) {
            // Not an async tool task - cancelled a background agent launched via the task tool.
            return ToolCallResult.completed("Task cancelled: " + taskId);
        }
        return result;
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
                    ToolCallParameters.ParamSpec.of(String.class, "action", "The action to perform: 'poll' or 'cancel'").required().enums(List.of("poll", "cancel")),
                    ToolCallParameters.ParamSpec.of(String.class, "task_id", "The task ID to poll or cancel").required()
            ));
            var tool = new AsyncTaskOutputTool();
            build(tool);
            return tool;
        }
    }
}
