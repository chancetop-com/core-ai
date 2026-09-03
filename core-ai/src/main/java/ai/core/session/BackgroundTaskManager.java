package ai.core.session;

import ai.core.agent.CancellationToken;
import ai.core.agent.Task;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BackgroundTaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundTaskManager.class);

    private static final int MAX_NOTIFICATION_RESULT_LENGTH = 30 * 1024;

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
    private final Map<String, RunningTask> runningTasks = new ConcurrentHashMap<>();
    private final SessionCommandQueue commandQueue;
    private final SubagentOutputSinkFactory sinkFactory;
    private final ExecutorService executor;
    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;

    public BackgroundTaskManager(SessionCommandQueue commandQueue, SubagentOutputSinkFactory sinkFactory, String sessionId, Consumer<AgentEvent> dispatcher) {
        this.commandQueue = commandQueue;
        this.sinkFactory = sinkFactory;
        this.executor = AsyncToolTaskExecutor.getInstance().getExecutor();
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    public TaskHandle submit(String taskId, Supplier<String> agentRunner, CancellationToken token) {
        LOGGER.debug("submitting background task, taskId={}", taskId);
        var sink = sinkFactory.create(taskId);
        var outputRef = sink.getReference();
        var notified = new AtomicBoolean(false);
        // Registered before the executor submit so notifyTerminal on the worker thread
        // always observes the entry (the reverse order could leave a stale entry behind).
        var running = new RunningTask(null, notified, outputRef);
        runningTasks.put(taskId, running);
        var otelContext = Context.current();
        var future = executor.submit(() -> {
            var scope = otelContext.makeCurrent();
            try {
                var runResult = runAgentWithSink(agentRunner, sink, taskId);
                LOGGER.debug("background task finished, taskId={}, status={}", taskId, runResult.status);
                notifyTerminal(taskId, runResult.status, runResult.result, runResult.error, running);
            } finally {
                scope.close();
            }
        });
        running.future = future;
        if (token != null) {
            token.onCancel(() -> {
                LOGGER.debug("token cancelled for background task, taskId={}", taskId);
                cancelRunning(taskId, running);
            });
        }
        return new TaskHandle(outputRef, future);
    }

    public void register(Task task) {
        LOGGER.debug("registering task, taskId={}", task.taskId);
        tasks.add(task);
    }

    /**
     * Cancels a running background task by task id, notifying the session so the
     * main agent learns about the cancellation.
     *
     * @return true if the task was found and cancelled, false if it is not running
     */
    public boolean cancel(String taskId) {
        var running = runningTasks.get(taskId);
        if (running == null) {
            return false;
        }
        LOGGER.debug("cancelling background task, taskId={}", taskId);
        return cancelRunning(taskId, running);
    }

    public boolean isRunning(String taskId) {
        return runningTasks.containsKey(taskId);
    }

    private boolean cancelRunning(String taskId, RunningTask running) {
        if (!running.notified.compareAndSet(false, true)) return false;
        runningTasks.remove(taskId, running);
        running.future.cancel(true);
        publishTerminal(taskId, "cancelled", running.outputRef, null, "cancelled by user");
        return true;
    }

    private void notifyTerminal(String taskId, String status, String result, String error, RunningTask running) {
        if (!running.notified.compareAndSet(false, true)) return;
        runningTasks.remove(taskId, running);
        publishTerminal(taskId, status, running.outputRef, result, error);
    }

    private void publishTerminal(String taskId, String status, String outputRef, String result, String error) {
        commandQueue.enqueueTaskNotification(buildNotificationXml(taskId, status, outputRef, result, error));
        dispatcher.accept(TaskStatusEvent.of(sessionId, taskId, status));
    }

    public void cancelAll() {
        LOGGER.debug("cancelling all tasks, count={}", tasks.size());
        tasks.forEach(Task::cancel);
    }

    public BackgroundTaskManager createChild() {
        return new BackgroundTaskManager(commandQueue, sinkFactory, sessionId, dispatcher);
    }

    public List<Task> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    private String buildNotificationXml(String taskId, String status, String outputRef, String result, String error) {
        var resultXml = "completed".equals(status)
                ? "<result>" + truncateResult(result, outputRef) + "</result>"
                : "<error>" + truncateResult(error, outputRef) + "</error>";
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        return "<task-notification>%n<task-id>%s</task-id>%n<status>%s</status>%n%s%s%n</task-notification>%n".formatted(taskId, status, outputRefXml, resultXml);
    }

    private String truncateResult(String text, String outputRef) {
        if (text == null || text.length() <= MAX_NOTIFICATION_RESULT_LENGTH) return text;
        var truncated = text.substring(0, MAX_NOTIFICATION_RESULT_LENGTH);
        var suffix = "\n\n[Output truncated: showing first " + MAX_NOTIFICATION_RESULT_LENGTH
                + " characters. Full output is available at: " + outputRef + "]";
        return truncated + suffix;
    }

    public record TaskHandle(String outputRef, Future<?> future) {
    }

    private static final class RunningTask {
        volatile Future<?> future;
        final AtomicBoolean notified;
        final String outputRef;

        private RunningTask(Future<?> future, AtomicBoolean notified, String outputRef) {
            this.future = future;
            this.notified = notified;
            this.outputRef = outputRef;
        }
    }

    private record TaskRunResult(String status, String result, String error) {
    }
}
