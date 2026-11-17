package ai.core.session;

import ai.core.agent.CancellationToken;
import ai.core.agent.Task;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.tool.wait.TaskCompletionRegistry;
import ai.core.tool.wait.TaskRef;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BackgroundTaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundTaskManager.class);

    private static final int MAX_NOTIFICATION_RESULT_LENGTH = 30 * 1024;
    private static final int MAX_TERMINAL_RESULT_LENGTH = 200 * 1024;
    private static final int MAX_TERMINAL_TASKS = 100;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static String normalizePath(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return path;
        }
    }

    private static boolean samePath(String left, String right) {
        var normalizedLeft = normalizePath(left);
        var normalizedRight = normalizePath(right);
        return IS_WINDOWS ? normalizedLeft.equalsIgnoreCase(normalizedRight) : normalizedLeft.equals(normalizedRight);
    }

    private static String terminalResult(String result, String outputRef) {
        if (result == null || result.length() <= MAX_TERMINAL_RESULT_LENGTH) return result;
        return result.substring(0, MAX_TERMINAL_RESULT_LENGTH)
                + "\n\n[Output truncated: full result is available at: " + (outputRef != null ? outputRef : "(not available)") + "]";
    }

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
    private final Map<String, TerminalTask> terminalTasks;
    private final ConsumedTasks consumedTasks;
    private final TaskCompletionRegistry completions;
    private final SessionCommandQueue commandQueue;
    private final SubagentOutputSinkFactory sinkFactory;
    private final ExecutorService executor;
    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;

    public BackgroundTaskManager(SessionCommandQueue commandQueue, SubagentOutputSinkFactory sinkFactory, String sessionId, Consumer<AgentEvent> dispatcher) {
        this(commandQueue, sinkFactory, sessionId, dispatcher, AsyncToolTaskExecutor.getInstance().getExecutor());
    }

    BackgroundTaskManager(SessionCommandQueue commandQueue, SubagentOutputSinkFactory sinkFactory, String sessionId, Consumer<AgentEvent> dispatcher, ExecutorService executor) {
        this(commandQueue, sinkFactory, sessionId, dispatcher, executor,
                new SharedTaskState(new ConcurrentHashMap<>(), new ConsumedTasks(),
                        new TaskCompletionRegistry("background", null)));
    }

    private BackgroundTaskManager(SessionCommandQueue commandQueue, SubagentOutputSinkFactory sinkFactory, String sessionId,
                                  Consumer<AgentEvent> dispatcher, ExecutorService executor, SharedTaskState sharedState) {
        this.commandQueue = commandQueue;
        this.sinkFactory = sinkFactory;
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.terminalTasks = sharedState.terminalTasks();
        this.consumedTasks = sharedState.consumedTasks();
        this.completions = sharedState.completions();
        commandQueue.setTaskNotificationFilter(this::isNotificationSuperseded);
    }

    public TaskHandle submit(String taskId, Supplier<String> agentRunner, CancellationToken token) {
        return submit(taskId, agentRunner, token, null, true);
    }

    /** onNeverStarted releases caller state when the task is cancelled before its runnable begins. */
    public TaskHandle submit(String taskId, Supplier<String> agentRunner, CancellationToken token, Runnable onNeverStarted) {
        return submit(taskId, agentRunner, token, onNeverStarted, true);
    }

    /**
     * @param notify whether the terminal state should start a notification turn. Fire-and-forget
     *               side effects can opt out and still be polled through async_task_output.
     */
    @SuppressWarnings("PMD.UseTryWithResources")
    public TaskHandle submit(String taskId, Supplier<String> agentRunner, CancellationToken token, Runnable onNeverStarted, boolean notify) {
        LOGGER.debug("submitting background task, taskId={}", taskId);
        // A reused id must not answer with the previous run's result while the new one starts.
        terminalTasks.remove(taskId);
        var sink = sinkFactory.create(taskId);
        var outputRef = sink.getReference();
        var notified = new AtomicBoolean(false);
        // Both registrations precede the executor submit: a waiter can never miss a completion, and
        // the worker always observes its running entry (a later order could leave a stale entry).
        var completion = completions.register(taskId, sessionId);
        var running = new RunningTask(null, notified, outputRef, onNeverStarted, notify, completion);
        runningTasks.put(taskId, running);
        var otelContext = Context.current();
        var future = executor.submit(() -> {
            running.started.set(true);
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

    public boolean hasRunningTasks() {
        return !runningTasks.isEmpty();
    }

    public int runningTaskCount() {
        return runningTasks.size();
    }

    public boolean hasTask(String taskId) {
        return runningTasks.containsKey(taskId) || terminalTasks.containsKey(taskId);
    }

    /** A fast status read for a background task; the terminal result stays available for later readers. */
    public ToolCallResult pollTask(String taskId) {
        var terminal = terminalTasks.get(taskId);
        if (terminal != null) return terminal.toResult();
        if (runningTasks.containsKey(taskId)) return ToolCallResult.pending(taskId, "Background task is still running");
        return ToolCallResult.failed("Task not found: " + taskId);
    }

    /**
     * Blocks until the task reaches a terminal state or the timeout expires. The caller consumes the
     * result, so its completion notification is suppressed when the session drains the queue.
     */
    public ToolCallResult awaitTask(String taskId, long timeoutMs) {
        var terminal = terminalTasks.get(taskId);
        if (terminal != null) return terminal.toResult();
        var running = runningTasks.get(taskId);
        if (running == null) return ToolCallResult.failed("Task not found: " + taskId);
        var future = running.future;
        if (future == null) return ToolCallResult.pending(taskId, "Background task is starting");
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ToolCallResult.pending(taskId, "Background task is still running after " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolCallResult.failed("Wait for task interrupted: " + taskId);
        } catch (ExecutionException e) {
            LOGGER.warn("background task wait failed, taskId={}, error={}", taskId, e.getMessage());
        } catch (java.util.concurrent.CancellationException e) {
            return ToolCallResult.failed("Task cancelled: " + taskId);
        }
        terminal = terminalTasks.get(taskId);
        return terminal != null ? terminal.toResult() : ToolCallResult.pending(taskId, "Background task is finishing");
    }

    public ToolCallResult cancelTask(String taskId) {
        if (terminalTasks.containsKey(taskId)) return ToolCallResult.failed("Task already finished: " + taskId);
        if (!cancel(taskId)) return ToolCallResult.failed("Task not found or not running: " + taskId);
        return ToolCallResult.completed("Task cancelled: " + taskId);
    }

    public void markConsumed(String taskId) {
        consumedTasks.mark(taskId);
    }

    /** Consumption is generation-scoped: a new run of the same id still owes its own notification. */
    public void markConsumed(TaskRef ref) {
        consumedTasks.mark(ref.taskId(), ref.generation());
    }

    public boolean isConsumed(String taskId) {
        return consumedTasks.isConsumed(taskId);
    }

    /**
     * The manager instance is already the session's authorization boundary: every context holding it
     * is entitled to the tasks it launched, so no second owner check is applied here. (Its session id
     * may differ from the agent context's, e.g. a test harness or a rebuilt session.)
     */
    public Optional<TaskCompletionRegistry.Entry> findCompletion(String taskId) {
        return completions.find(taskId);
    }

    /** Consumed or wait-suppressed notifications are dropped when the session drains its queue. */
    private boolean isNotificationSuperseded(String taskId, Long generation) {
        return consumedTasks.isConsumed(taskId, generation)
                || completions.isNotificationSuppressed(taskId, generation);
    }

    /** Reading a finished task's output file is an implicit consumption: the result already reached the model. */
    public void markOutputRead(String path) {
        if (path == null || path.isBlank()) return;
        terminalTasks.forEach((taskId, task) -> {
            if (task.outputRef != null && samePath(task.outputRef, path)) consumedTasks.mark(taskId);
        });
    }

    private void recordTerminal(String taskId, String status, String result, String error, String outputRef,
                                TaskCompletionRegistry.Entry completion) {
        var terminal = new TerminalTask(status, terminalResult(result, outputRef), error, outputRef);
        terminalTasks.put(taskId, terminal);
        trimTerminalTasks();
        completion.complete(terminal.toResult().withStats("taskStatus", status));
    }

    private void trimTerminalTasks() {
        while (terminalTasks.size() > MAX_TERMINAL_TASKS) {
            terminalTasks.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().completedAtMs))
                    // Notification suppression and consumption are generation tombstones and deliberately
                    // outlive the terminal result, so an already-queued notification cannot resurrect.
                    .ifPresent(entry -> terminalTasks.remove(entry.getKey()));
        }
    }

    private boolean cancelRunning(String taskId, RunningTask running) {
        if (!running.notified.compareAndSet(false, true)) return false;
        runningTasks.remove(taskId, running);
        running.future.cancel(true);
        runNeverStarted(taskId, running);
        recordTerminal(taskId, "cancelled", null, "cancelled by user", running.outputRef, running.completion);
        publishTerminal(taskId, "cancelled", running.outputRef, null, "cancelled by user", running);
        return true;
    }

    /**
     * The cancelled future can no longer execute a runnable that had not begun, so the caller's
     * cleanup would never run; hand it the release instead of leaving the armed state behind.
     */
    private void runNeverStarted(String taskId, RunningTask running) {
        if (running.started.get() || running.onNeverStarted == null) return;
        try {
            running.onNeverStarted.run();
        } catch (RuntimeException e) {
            LOGGER.warn("background task never-started callback failed, taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private void notifyTerminal(String taskId, String status, String result, String error, RunningTask running) {
        if (!running.notified.compareAndSet(false, true)) return;
        runningTasks.remove(taskId, running);
        recordTerminal(taskId, status, result, error, running.outputRef, running.completion);
        publishTerminal(taskId, status, running.outputRef, result, error, running);
    }

    private void publishTerminal(String taskId, String status, String outputRef, String result, String error,
                                 RunningTask running) {
        // A live wait suppresses only the automatic turn; the UI status event below is always delivered.
        if (running.notify && !running.completion.isNotificationSuppressed()) {
            commandQueue.enqueueTaskNotification(buildNotificationXml(taskId, status, outputRef, result, error,
                    running.completion.ref().generation()));
        }
        dispatcher.accept(TaskStatusEvent.of(sessionId, taskId, status));
    }

    public void cancelAll() {
        LOGGER.debug("cancelling all tasks, count={}", tasks.size());
        tasks.forEach(Task::cancel);
    }

    public void cancelAllRunning() {
        LOGGER.debug("cancelling all running tasks, count={}", runningTasks.size());
        for (var taskId : List.copyOf(runningTasks.keySet())) {
            var running = runningTasks.get(taskId);
            if (running != null) cancelRunning(taskId, running);
        }
    }

    public BackgroundTaskManager createChild() {
        return new BackgroundTaskManager(commandQueue, sinkFactory, sessionId, dispatcher, executor,
                new SharedTaskState(terminalTasks, consumedTasks, completions));
    }

    public List<Task> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    private String buildNotificationXml(String taskId, String status, String outputRef, String result, String error,
                                        long generation) {
        var resultXml = "completed".equals(status)
                ? "<result>" + truncateResult(result, outputRef) + "</result>"
                : "<error>" + truncateResult(error, outputRef) + "</error>";
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        return "<task-notification>%n<task-id>%s</task-id>%n<task-generation>%d</task-generation>%n<status>%s</status>%n%s%s%n</task-notification>%n"
                .formatted(taskId, generation, status, outputRefXml, resultXml);
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
        final AtomicBoolean started = new AtomicBoolean(false);
        final String outputRef;
        final Runnable onNeverStarted;
        final boolean notify;
        final TaskCompletionRegistry.Entry completion;

        private RunningTask(Future<?> future, AtomicBoolean notified, String outputRef, Runnable onNeverStarted,
                            boolean notify, TaskCompletionRegistry.Entry completion) {
            this.future = future;
            this.notified = notified;
            this.outputRef = outputRef;
            this.onNeverStarted = onNeverStarted;
            this.notify = notify;
            this.completion = completion;
        }
    }

    private record TaskRunResult(String status, String result, String error) {
    }

    private record SharedTaskState(Map<String, TerminalTask> terminalTasks, ConsumedTasks consumedTasks,
                                   TaskCompletionRegistry completions) {
    }

    /** Consumption tombstones are generation-keyed: a re-run owes a notification, old ones stay dropped. */
    private static final class ConsumedTasks {
        private static final String ALL_GENERATIONS = "#*";

        private final Set<String> keys = ConcurrentHashMap.newKeySet();

        private void mark(String taskId) {
            keys.add(taskId + ALL_GENERATIONS);
        }

        private void mark(String taskId, long generation) {
            keys.add(taskId + "#" + generation);
        }

        private boolean isConsumed(String taskId) {
            var prefix = taskId + "#";
            return keys.stream().anyMatch(key -> key.startsWith(prefix));
        }

        private boolean isConsumed(String taskId, Long generation) {
            if (keys.contains(taskId + ALL_GENERATIONS)) return true;
            if (generation == null) return isConsumed(taskId);
            return keys.contains(taskId + "#" + generation);
        }
    }

    private record TerminalTask(String status, String result, String error, String outputRef, long completedAtMs) {
        private TerminalTask(String status, String result, String error, String outputRef) {
            this(status, result, error, outputRef, System.currentTimeMillis());
        }

        private ToolCallResult toResult() {
            return "completed".equals(status)
                    ? ToolCallResult.completed(result != null ? result : "")
                    : ToolCallResult.failed(error != null ? error : status);
        }
    }
}
