package ai.core.session;

import ai.core.agent.CancellationToken;
import ai.core.agent.Task;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class BackgroundTaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundTaskManager.class);

    private static TaskRunResult runAgentWithSink(Supplier<String> agentRunner, SubagentOutputSink sink, String taskId) {
        String status;
        String result = null;
        String error = null;
        try {
            result = agentRunner.get();
            sink.write(result != null ? result : "");
            status = "completed";
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                status = "cancelled";
                error = e.getMessage();
                LOGGER.debug("background task interrupted, taskId={}", taskId);
            } else {
                status = "failed";
                error = e.getMessage();
                LOGGER.warn("background task failed, taskId={}, error={}", taskId, e.getMessage());
            }
        } finally {
            sink.close();
        }
        return new TaskRunResult(status, result, error);
    }

    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final SessionCommandQueue commandQueue;
    private final SubagentOutputSinkFactory sinkFactory;
    private final ExecutorService executor;

    public BackgroundTaskManager(SessionCommandQueue commandQueue, SubagentOutputSinkFactory sinkFactory) {
        this.commandQueue = commandQueue;
        this.sinkFactory = sinkFactory;
        this.executor = AsyncToolTaskExecutor.getInstance().getExecutor();
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    public TaskHandle submit(String taskId, Supplier<String> agentRunner, CancellationToken token) {
        LOGGER.debug("submitting background task, taskId={}", taskId);
        var sink = sinkFactory.create(taskId);
        var outputRef = sink.getReference();
        var notified = new AtomicBoolean(false);
        var otelContext = Context.current();
        var future = executor.submit(() -> {
            var scope = otelContext.makeCurrent();
            try {
                var runResult = runAgentWithSink(agentRunner, sink, taskId);
                LOGGER.debug("background task finished, taskId={}, status={}", taskId, runResult.status);
                if (notified.compareAndSet(false, true)) {
                    commandQueue.enqueueTaskNotification(buildNotificationXml(taskId, runResult.status, outputRef, runResult.result, runResult.error));
                }
            } finally {
                scope.close();
            }
        });
        if (token != null) {
            token.onCancel(() -> {
                LOGGER.debug("token cancelled for background task, taskId={}", taskId);
                future.cancel(true);
                // Send cancellation notification immediately. The executor lambda also
                // sends a "cancelled" status when it detects the interruption, but if the
                // task hasn't started yet, future.cancel(true) prevents execution entirely
                // and the lambda never runs. The notified CAS ensures exactly one notification.
                if (notified.compareAndSet(false, true)) {
                    commandQueue.enqueueTaskNotification(
                            buildNotificationXml(taskId, "cancelled", outputRef, null, "cancelled by user"));
                }
            });
        }
        return new TaskHandle(outputRef, future);
    }

    public void register(Task task) {
        LOGGER.debug("registering task, taskId={}", task.taskId);
        tasks.add(task);
    }

    public void cancelAll() {
        LOGGER.debug("cancelling all tasks, count={}", tasks.size());
        tasks.forEach(Task::cancel);
    }

    public BackgroundTaskManager createChild() {
        return new BackgroundTaskManager(commandQueue, sinkFactory);
    }

    public List<Task> getTasks() {
        return Collections.unmodifiableList(tasks);
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

    public record TaskHandle(String outputRef, Future<?> future) {
    }

    private record TaskRunResult(String status, String result, String error) {
    }
}
