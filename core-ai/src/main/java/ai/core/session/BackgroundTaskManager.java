package ai.core.session;

import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.tool.subagent.SubagentOutputSinkFactory;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Submits background subagent tasks and enqueues completion notifications
 * into SessionCommandQueue when tasks finish.
 */
public class BackgroundTaskManager {

    private final SessionCommandQueue commandQueue;
    private final SubagentOutputSinkFactory sinkFactory;
    private final ExecutorService executor;

    public BackgroundTaskManager(SessionCommandQueue commandQueue, SubagentOutputSinkFactory sinkFactory) {
        this.commandQueue = commandQueue;
        this.sinkFactory = sinkFactory;
        this.executor = AsyncToolTaskExecutor.getInstance().getExecutor();
    }

    /**
     * Submits an agent task to run in the background.
     * Returns the output reference for the caller to include in the tool response.
     */
    public String submit(String taskId, Supplier<String> agentRunner) {
        var sink = sinkFactory.create(taskId);
        var outputRef = sink.getReference();
        executor.submit(() -> {
            String status;
            String result = null;
            String error = null;
            try {
                result = agentRunner.get();
                sink.write(result != null ? result : "");
                sink.close();
                status = "completed";
            } catch (Exception e) {
                sink.close();
                status = "failed";
                error = e.getMessage();
            }
            commandQueue.enqueueTaskNotification(buildNotificationXml(taskId, status, outputRef, result, error));
        });
        return outputRef;
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
