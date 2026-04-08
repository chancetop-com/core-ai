package ai.core.session;

import ai.core.tool.subagent.SubagentTaskRegistry.SubagentResult;

import java.util.concurrent.CompletableFuture;

/**
 * Watches background subagent futures and enqueues TASK_NOTIFICATION
 * into SessionCommandQueue when tasks complete.
 */
public class BackgroundTaskMonitor {

    private final SessionCommandQueue commandQueue;

    public BackgroundTaskMonitor(SessionCommandQueue commandQueue) {
        this.commandQueue = commandQueue;
    }

    public void watch(String taskId, CompletableFuture<SubagentResult> future) {
        future.whenComplete((result, ex) -> {
            String notification;
            if (ex != null) {
                notification = buildNotificationXml(taskId, "failed", null, null, ex.getMessage());
            } else {
                notification = buildNotificationXml(
                        result.taskId(), result.status(),
                        result.outputRef(), result.result(), result.error());
            }
            commandQueue.enqueueTaskNotification(notification);
        });
    }

    private String buildNotificationXml(String taskId, String status, String outputRef, String result, String error) {
        var resultXml = "completed".equals(status)
                ? "<result>" + result + "</result>"
                : "<error>" + error + "</error>";
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        return """
                <task-notification>
                <task-id>%s</task-id>
                <status>%s</status>
                %s%s
                </task-notification>
                """.formatted(taskId, status, outputRefXml, resultXml);
    }
}
