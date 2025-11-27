package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.ToolCallAsyncTask;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AsyncTaskOutputTool extends ToolCall {
    public static final String TOOL_NAME = "async_task_output";

    private static final String TOOL_DESC = """
        Query the status and output of async tasks.
        
        Use action='poll' with task_id to check a specific task, or action='cancel' with task_id to cancel a task.
        """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            // Get task manager from execution context
            var taskManager = context.getAsyncTaskManager();
            if (taskManager == null) {
                return ToolCallResult.failed("Error: ToolCallAsyncTaskManager not available in execution context.")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var args = parseArguments(arguments);
            var action = (String) args.get("action");
            var taskId = (String) args.get("task_id");

            if (taskId == null || taskId.isBlank()) {
                return ToolCallResult.failed("Error: task_id is required.")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            String result = switch (action) {
                case "poll" -> pollTask(taskManager, taskId);
                case "cancel" -> cancelTask(taskManager, taskId);
                default -> "Unknown action: " + action + ". Use 'poll' or 'cancel'.";
            };

            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", action);
        } catch (Exception e) {
            return ToolCallResult.failed("Error: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("Error: AsyncTaskOutputTool requires ExecutionContext. Use execute(arguments, context) instead.");
    }

    private String pollTask(ToolCallAsyncTaskManager taskManager, String taskId) {
        var taskOpt = taskManager.loadTask(taskId);
        if (taskOpt.isEmpty()) {
            return "Task not found: " + taskId;
        }

        var task = taskOpt.get();

        // If task is still pending, poll it
        if (task.isPending()) {
            var result = taskManager.pollTask(taskId);
            return formatPollResult(taskId, result, task);
        } else if (task.isWaitingForInput()) {
            return formatTaskInfo(task) + "\nThis task is waiting for user input.";
        } else {
            return "Task " + taskId + " is in terminal state: " + task.status();
        }
    }

    private String cancelTask(ToolCallAsyncTaskManager taskManager, String taskId) {
        var result = taskManager.cancelTask(taskId);
        if (result.isFailed()) {
            return "Failed to cancel task: " + result.getResult();
        }
        return "Task " + taskId + " has been cancelled.";
    }

    private String formatTaskInfo(ToolCallAsyncTask task) {
        var createdAgo = formatDuration(task.createdAt()) + " ago";
        var sb = new StringBuilder(256)
            .append("- Task ID: ").append(task.taskId())
            .append("\n  Tool: ").append(task.tool().getName())
            .append("\n  Status: ").append(task.status())
            .append("\n  Created: ").append(createdAgo)
            .append("\n  Poll Count: ").append(task.pollCount()).append('\n');
        if (task.lastPolledAt() != null) {
            var polledAgo = formatDuration(task.lastPolledAt()) + " ago\n";
            sb.append("  Last Polled: ").append(polledAgo);
        }
        if (task.lastResult() != null && task.lastResult().getResult() != null) {
            var resultPreview = task.lastResult().getResult();
            if (resultPreview.length() > 100) {
                resultPreview = resultPreview.substring(0, 100) + "...";
            }
            sb.append("  Last Result: ").append(resultPreview).append('\n');
        }
        return sb.toString();
    }

    private String formatPollResult(String taskId, ToolCallResult result, ToolCallAsyncTask originalTask) {
        var sb = new StringBuilder(128)
            .append("Task: ").append(taskId)
            .append("\nTool: ").append(originalTask.tool().getName())
            .append("\nStatus: ").append(result.getStatus())
            .append("\nRunning Time: ").append(formatDuration(originalTask.createdAt())).append('\n');

        if (result.isCompleted()) {
            sb.append("\n[TASK COMPLETED]\nResult:\n").append(result.getResult());
        } else if (result.isFailed()) {
            sb.append("\n[TASK FAILED]\nError: ").append(result.getResult());
        } else if (result.isPending()) {
            sb.append("\n[TASK STILL RUNNING]\n");
            if (result.getResult() != null) {
                sb.append("Progress: ").append(result.getResult());
            }
        }

        return sb.toString();
    }

    private String formatDuration(Instant from) {
        var duration = Duration.between(from, Instant.now());
        if (duration.toHours() > 0) {
            return duration.toHours() + "h " + duration.toMinutesPart() + "m";
        } else if (duration.toMinutes() > 0) {
            return duration.toMinutes() + "m " + duration.toSecondsPart() + "s";
        } else {
            return duration.toSeconds() + "s";
        }
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
                    ToolCallParameters.ParamSpec.of(String.class, "task_id", "The task ID to poll or cancel (required)").required()
            ));
            var tool = new AsyncTaskOutputTool();
            build(tool);
            return tool;
        }
    }
}
