package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
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

    private static final String TOOL_DESC = """
            Query the status and output of async tasks.

            Use action='poll' with task_id to check a specific task's status and get its result when completed.
            Use action='cancel' with task_id to cancel a running task.

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
        return execute(arguments);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        var startTime = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            var action = (String) args.get("action");
            var taskId = (String) args.get("task_id");

            if (taskId == null || taskId.isBlank()) {
                return ToolCallResult.failed("Error: task_id is required.")
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            var executor = AsyncToolTaskExecutor.getInstance();
            var result = switch (action) {
                case "poll" -> pollTask(executor, taskId);
                case "cancel" -> cancelTask(executor, taskId);
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

    private ToolCallResult pollTask(AsyncToolTaskExecutor executor, String taskId) {
        var result = executor.poll(taskId);

        var taskInfo = executor.getTaskInfo(taskId);
        if (taskInfo.isPresent()) {
            var info = taskInfo.get();
            result.withStats("toolName", info.toolName())
                    .withStats("elapsedMs", info.elapsedMs());
        }

        return result;
    }

    private ToolCallResult cancelTask(AsyncToolTaskExecutor executor, String taskId) {
        return executor.cancel(taskId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        try {
            return core.framework.json.JSON.fromJSON(Map.class, arguments);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON arguments: " + arguments, e);
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
                    ToolCallParameters.ParamSpec.of(String.class, "action", "The action to perform: 'poll' or 'cancel'").required().enums(List.of("poll", "cancel")),
                    ToolCallParameters.ParamSpec.of(String.class, "task_id", "The task ID to poll or cancel").required()
            ));
            var tool = new AsyncTaskOutputTool();
            build(tool);
            return tool;
        }
    }
}
