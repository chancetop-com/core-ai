package ai.core.tool.wait;

import ai.core.agent.CancelReason;
import ai.core.agent.CancellationException;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentWaitEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns a {@link WaitRequest} into a parked turn: resolve the task source, subscribe to its
 * completion, and block the calling thread until the terminal snapshot arrives or the caller's
 * deadline/cancellation wins. The result is a normal tool result for the original call id — no
 * intermediate PENDING and no new user turn.
 *
 * @author stephen
 */
public final class TaskAwaitCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskAwaitCoordinator.class);
    private static final String SOURCE_BACKGROUND = "background";
    private static final String SOURCE_EXECUTOR = "executor";

    private static final TaskAwaitCoordinator INSTANCE = new TaskAwaitCoordinator();

    public static TaskAwaitCoordinator getInstance() {
        return INSTANCE;
    }

    private TaskAwaitCoordinator() {
    }

    public ToolCallResult await(WaitRequest request, FunctionCall functionCall, ExecutionContext context) {
        var scope = WaitExecutionScope.of(context, functionCall != null ? functionCall.id : null);
        var startedAt = System.currentTimeMillis();
        if (request.timeoutMs() < 0) {
            return failure("timeout_ms must be >= 0", null, startedAt, "invalid_timeout");
        }
        if (context.getWaitCapability() != WaitCapability.ENABLED) {
            return failure("WAIT_UNSUPPORTED: this host does not allow an agent to park on an async task; "
                    + "use action='poll' and continue", null, startedAt, "unsupported");
        }
        if (request.taskId().equals(context.getTaskId())) {
            return failure("WAIT_UNAVAILABLE: an execution cannot wait on the task that owns it", null,
                    startedAt, "unavailable");
        }

        var resolved = resolve(request.taskId(), scope, context);
        if (resolved.isEmpty()) {
            return failure("WAIT_UNAVAILABLE: task is not available to this session: " + request.taskId(),
                    null, startedAt, "unavailable");
        }
        var target = resolved.get();
        if (target.unsupportedReason() != null) {
            return failure(target.unsupportedReason(), null, startedAt, "unsupported");
        }

        var entry = target.entry();
        var waiter = entry.subscribe();
        var budget = effectiveTimeout(request.timeoutMs(), context);
        if (budget.immediateFailure() != null) {
            waiter.close();
            return failure(budget.immediateFailure(), entry.ref(), startedAt, "timeout");
        }

        try {
            emit(context, AgentWaitEvent.WAITING, entry.ref(), scope, budget.timeoutMs(), startedAt);
            return awaitTerminal(context, waiter, target, scope, budget, startedAt);
        } finally {
            emit(context, AgentWaitEvent.CLEARED, entry.ref(), scope, budget.timeoutMs(), startedAt);
        }
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    private ToolCallResult awaitTerminal(ExecutionContext context, TaskCompletionRegistry.Waiter waiter,
                                         Resolution target, WaitExecutionScope scope, Budget budget, long startedAt) {
        var entry = target.entry();
        var cancelReason = new AtomicReference<String>();
        var token = scope.cancellationToken();
        Runnable unbind = token == null ? () -> {
        } : token.onCancel(() -> {
            cancelReason.set("WAIT_CANCELLED: the turn was cancelled while waiting for task " + entry.ref().taskId());
            waiter.cancel(cancelReason.get());
        });
        try {
            if (token != null) token.throwIfCancelled();
            var terminal = budget.timeoutMs() > 0
                    ? waiter.future().get(budget.timeoutMs(), TimeUnit.MILLISECONDS)
                    : waiter.future().get();
            if (cancelReason.get() != null) {
                return failure(cancelReason.get(), entry.ref(), startedAt, "cancelled");
            }
            emit(context, AgentWaitEvent.RESUMING, entry.ref(), scope, budget.timeoutMs(), startedAt);
            return completed(terminal, entry.ref(), startedAt);
        } catch (TimeoutException e) {
            // The completion may have landed between the deadline check and this catch; deliver it
            // rather than reporting a timeout for a task that is already finished.
            var raced = entry.peek();
            if (raced != null) {
                emit(context, AgentWaitEvent.RESUMING, entry.ref(), scope, budget.timeoutMs(), startedAt);
                return completed(raced, entry.ref(), startedAt);
            }
            return timeoutResult(target, waiter, scope, budget, startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            waiter.cancel("WAIT_CANCELLED: interrupted while waiting for task " + entry.ref().taskId());
            return failure("WAIT_CANCELLED: interrupted while waiting for task " + entry.ref().taskId(),
                    entry.ref(), startedAt, "cancelled");
        } catch (CancellationException e) {
            var reason = cancelReason.get() != null ? cancelReason.get()
                    : "WAIT_CANCELLED: the turn was cancelled while waiting for task " + entry.ref().taskId();
            return failure(reason, entry.ref(), startedAt, "cancelled");
        } catch (ExecutionException e) {
            var cause = e.getCause();
            LOGGER.warn("async wait failed, taskId={}", entry.ref().taskId(), cause);
            return failure("WAIT_CANCELLED: wait failed: " + (cause != null ? cause.getMessage() : e.getMessage()),
                    entry.ref(), startedAt, "cancelled");
        } finally {
            unbind.run();
            waiter.close();
        }
    }

    private ToolCallResult timeoutResult(Resolution target, TaskCompletionRegistry.Waiter waiter,
                                         WaitExecutionScope scope, Budget budget, long startedAt) {
        var entry = target.entry();
        waiter.cancel("WAIT_TIMEOUT: task " + entry.ref().taskId() + " is still running after " + budget.timeoutMs() + "ms");
        releaseSuppression(entry, target.source());
        // The run's hard time budget (not the caller's own bound) expired: end the turn now instead of
        // letting the loop attempt a model call the budget will reject anyway.
        if (budget.budgetLimited() && scope.cancellationToken() != null) {
            scope.cancellationToken().cancel(CancelReason.TIMEOUT);
        }
        LOGGER.debug("async wait timed out, taskId={}, timeoutMs={}, budgetLimited={}",
                entry.ref().taskId(), budget.timeoutMs(), budget.budgetLimited());
        return failure("WAIT_TIMEOUT: task " + entry.ref().taskId() + " is still running after "
                        + budget.timeoutMs() + "ms (the task itself was not cancelled)", entry.ref(), startedAt, "timeout")
                .withStats("waitBudgetLimited", budget.budgetLimited());
    }

    private Optional<Resolution> resolve(String taskId, WaitExecutionScope scope, ExecutionContext context) {
        var taskManager = context.getTaskManager();
        if (taskManager != null && taskManager.hasTask(taskId)) {
            var entry = taskManager.findCompletion(taskId);
            if (entry.isEmpty()) {
                return Optional.of(new Resolution(null, null, "WAIT_UNAVAILABLE: task state is no longer retained: " + taskId));
            }
            if (!entry.get().isDone()) {
                entry.get().suppressAutoNotification();
            }
            return Optional.of(new Resolution(SOURCE_BACKGROUND, entry.get(), null));
        }
        var executor = AsyncToolTaskExecutor.getInstance();
        if (executor.getTaskInfo(taskId).isPresent()) {
            var entry = executor.findCompletion(taskId, scope.ownerScope());
            if (entry.isEmpty()) {
                return Optional.of(new Resolution(null, null, "WAIT_UNAVAILABLE: task state is no longer retained: " + taskId));
            }
            return Optional.of(new Resolution(SOURCE_EXECUTOR, entry.get(), null));
        }
        var managed = context.getAsyncTaskManager();
        if (managed != null && managed.loadTask(taskId).isPresent()) {
            return Optional.of(new Resolution(null, null,
                    "WAIT_UNSUPPORTED: managed tasks can only be waited on by a host with a completion producer "
                            + "(this host has a poll-only task store); use action='poll'"));
        }
        return Optional.empty();
    }

    private Budget effectiveTimeout(long requestedTimeoutMs, ExecutionContext context) {
        var timeout = requestedTimeoutMs;
        var budgetLimited = false;
        var budget = context.getRunBudget();
        if (budget != null && budget.time() != null) {
            long available = budget.time().remainingMillis() - budget.time().reserveMillis();
            if (available <= 0) {
                return new Budget(0, true, "WAIT_TIMEOUT: the run time budget is exhausted");
            }
            if (timeout <= 0 || timeout > available) {
                timeout = available;
                budgetLimited = true;
            }
        }
        return new Budget(timeout, budgetLimited, null);
    }

    private ToolCallResult completed(ToolCallResult terminal, TaskRef ref, long startedAt) {
        var copy = terminal.snapshotCopy();
        copy.withStats("taskId", ref.taskId())
                .withStats("taskBackend", ref.backend())
                .withStats("taskGeneration", ref.generation())
                .withStats("waitedMs", System.currentTimeMillis() - startedAt);
        if (copy.isCompleted()) {
            return copy.withStats("waitOutcome", "completed");
        }
        if (copy.isFailed()) {
            var cancelled = "cancelled".equals(copy.getStats().get("taskStatus"));
            return copy.withStats("waitOutcome", cancelled ? "cancelled" : "failed");
        }
        return failure("WAIT_UNAVAILABLE: task " + ref.taskId() + " produced no terminal result",
                ref, startedAt, "unavailable");
    }

    private ToolCallResult failure(String message, TaskRef ref, long startedAt, String outcome) {
        var result = ToolCallResult.failed(message)
                .withStats("waitOutcome", outcome)
                .withStats("waitedMs", System.currentTimeMillis() - startedAt);
        if (ref != null) {
            result.withStats("taskId", ref.taskId())
                    .withStats("taskBackend", ref.backend())
                    .withStats("taskGeneration", ref.generation());
        }
        return result;
    }

    private void releaseSuppression(TaskCompletionRegistry.Entry entry, String source) {
        if (SOURCE_BACKGROUND.equals(source) && !entry.isDone()) {
            entry.releaseAutoNotification();
        }
    }

    private void emit(ExecutionContext context, String phase, TaskRef ref, WaitExecutionScope scope,
                      long timeoutMs, long startedAt) {
        if (context == null) return;
        var listener = context.getWaitEventListener();
        if (listener == null) return;
        var event = AgentWaitEvent.of(scope.ownerScope(), phase, ref.taskId());
        event.toolCallId = scope.toolCallId();
        event.backend = ref.backend();
        event.generation = ref.generation();
        event.deadlineMs = timeoutMs > 0 ? startedAt + timeoutMs : null;
        event.waitedMs = System.currentTimeMillis() - startedAt;
        try {
            listener.onWait(event);
        } catch (RuntimeException e) {
            LOGGER.debug("wait event listener failed, taskId={}", ref.taskId(), e);
        }
    }

    private record Resolution(String source, TaskCompletionRegistry.Entry entry, String unsupportedReason) {
    }

    private record Budget(long timeoutMs, boolean budgetLimited, String immediateFailure) {
    }
}
