package ai.core.tool.tools;

import ai.core.agent.budget.BudgetProfile;
import ai.core.agent.budget.RunBudget;
import ai.core.tool.ToolCallResult;

/**
 * Result decoration and budget admission for the shell tool, kept out of the tool class so its
 * execute path stays readable.
 *
 * @author stephen
 */
final class ShellToolSupport {
    static Admission admit(RunBudget budget, BudgetProfile profile, long requestedMs) {
        if (budget == null) return new Admission(true, null, requestedMs);
        var decision = budget.admitToolCall(profile, requestedMs);
        return new Admission(decision.allowed(), decision.reason(), decision.effectiveTimeoutMs());
    }

    static ToolCallResult withTimeoutNotice(ToolCallResult result, long requested, long effective) {
        if (effective >= requested) return result;
        return result.withStats("budget.timeout.requested_ms", requested)
                .withStats("budget.timeout.effective_ms", effective);
    }

    static String asyncLaunchedNotificationXml(String taskId, String outputRef, String description, boolean notify) {
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        var notification = notify
                ? "You will be notified automatically when it completes."
                : "Completion notification is disabled (notify=false); poll it with " + AsyncTaskOutputTool.TOOL_NAME + " if you need the result.";
        var reminder = "  Async bash launched successfully.%n  The bash packaged as a task is working in the background. %s%n  task id: %s (internal ID - do not mention to user)%n  If your next step depends on this command's result, call %s with action='wait' and task_id=%s before answering; wait returns the result in this turn and suppresses the completion notification.%n  Do not duplicate this task's work — avoid working with the same files or topics it is using. Work on non-overlapping tasks, or briefly tell the user what you launched and end%n  your response.%n  output_file: %s%n  If asked, you can check progress before completion by using %s or %s tail on the output file.%n"
                .formatted(notification, taskId, AsyncTaskOutputTool.TOOL_NAME, taskId, outputRef, ReadFileTool.TOOL_NAME, ShellCommandTool.TOOL_NAME);
        return "<task-notification>%n<task-id>%s</task-id>%n<task-type>%s</task-type>%n<task-description>%s</task-description>%n<status>%s</status>%n%s%n<system-reminder>%s</system-reminder>%n</task-notification>%n".formatted(taskId, "bash", description, "async_launched", outputRefXml, reminder);
    }

    private ShellToolSupport() {
    }

    record Admission(boolean accepted, String reason, long timeoutMs) {
    }
}
