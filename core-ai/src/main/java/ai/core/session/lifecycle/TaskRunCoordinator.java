package ai.core.session.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The session-side truth for one continuous task run. It does not decide business success; it only
 * records that the run authorized by one root input has no more required work and no pending result
 * continuation. LLM steps and driver invocations are reported into it, never inferred from text.
 *
 * @author stephen
 */
public final class TaskRunCoordinator {
    public static final String COVERAGE_COMPLETE = "complete";
    public static final String COVERAGE_UNTRACKABLE = "untrackable";
    public static final String COVERAGE_UNKNOWN = "unknown";
    private static final String REASON_RETURNED = "required_work_settled_and_processing_finished";
    private static final String REASON_JOB_FAILED = "required_job_failed_without_handler";
    private static final String REASON_COVERAGE = "required_work_coverage_incomplete";

    private final String sessionId;
    private final Object lock = new Object();
    private volatile String sessionIncarnation;
    private volatile TaskRunObserver observer;
    private volatile BooleanSupplier pendingWorkProbe = () -> false;
    private TaskRun active;
    private TaskRun lastEnded;
    private final Map<String, TaskRun> queuedRuns = new LinkedHashMap<>();
    private long runSequence;

    public TaskRunCoordinator(String sessionId, String sessionIncarnation) {
        this.sessionId = sessionId;
        this.sessionIncarnation = sessionIncarnation;
    }

    public String sessionId() {
        return sessionId;
    }

    public String sessionIncarnation() {
        return sessionIncarnation;
    }

    public void setSessionIncarnation(String sessionIncarnation) {
        this.sessionIncarnation = sessionIncarnation;
    }

    public void setObserver(TaskRunObserver observer) {
        this.observer = observer;
    }

    /** Backstop for work that has not reached its own hook yet. */
    public void setPendingWorkProbe(BooleanSupplier pendingWorkProbe) {
        this.pendingWorkProbe = pendingWorkProbe != null ? pendingWorkProbe : () -> false;
    }

    public TaskRunSnapshot openRoot(String requestId) {
        return openRoot(requestId, null);
    }

    public TaskRunSnapshot openRoot(String requestId, String turnId) {
        TaskRun.Emission emission;
        TaskRunSnapshot snapshot;
        synchronized (lock) {
            if (active == null || active.terminal()) {
                var run = new TaskRun("run-" + UUID.randomUUID(), ++runSequence, requestId, turnId, false,
                        System.currentTimeMillis());
                active = run;
                TaskRun.touch(run);
                snapshot = run.snapshot(sessionId, sessionIncarnation);
                emission = new TaskRun.Emission(snapshot, null, true);
            } else {
                var key = turnId != null ? turnId : requestId;
                var existing = queuedRuns.get(key);
                if (existing != null && !existing.terminal()) {
                    snapshot = existing.snapshot(sessionId, sessionIncarnation);
                    emission = null;
                } else {
                    var run = new TaskRun("run-" + UUID.randomUUID(), ++runSequence, requestId, turnId, true,
                            System.currentTimeMillis());
                    queuedRuns.put(key, run);
                    TaskRun.touch(run);
                    snapshot = run.snapshot(sessionId, sessionIncarnation);
                    emission = new TaskRun.Emission(snapshot, null, true);
                }
            }
            lock.notifyAll();
        }
        emit(emission);
        return snapshot;
    }

    public void requestQueued() {
        requestQueued(null);
    }

    public void requestQueued(String turnId) {
        TaskRun.Emission emission;
        synchronized (lock) {
            if (turnId != null && queuedRuns.containsKey(turnId)) {
                TaskRun.touch(queuedRuns.get(turnId));
                emission = new TaskRun.Emission(queuedRuns.get(turnId).snapshot(sessionId, sessionIncarnation), null, false);
            } else {
                emission = changeLocked(run -> run.queuedRequests++);
            }
            lock.notifyAll();
        }
        emit(emission);
    }

    public void discardQueued(String turnId, String reason) {
        TaskRun.Emission emission = null;
        synchronized (lock) {
            if (turnId == null) return;
            var run = queuedRuns.remove(turnId);
            if (run != null) {
                finishQueued(run, TaskRunOutcome.INTERRUPTED, reason);
                emission = new TaskRun.Emission(run.snapshot(sessionId, sessionIncarnation), run.ended(sessionId, sessionIncarnation), false);
            }
            lock.notifyAll();
        }
        emit(emission);
    }

    /** Promotes the queued run whose turn is now at the driver head. */
    public TaskRunSnapshot claimQueued(java.util.List<String> turnIds) {
        TaskRun.Emission emission;
        TaskRun.Emission superseded = null;
        TaskRunSnapshot snapshot;
        synchronized (lock) {
            if (active != null && active.terminal()) active = null;
            String queuedKey = null;
            for (var turnId : turnIds) {
                if (turnId != null && queuedRuns.containsKey(turnId)) {
                    queuedKey = turnId;
                    break;
                }
            }
            if (queuedKey == null) return null;
            if (active != null) {
                if (active.state() != TaskRunState.NEEDS_ATTENTION) {
                    lock.notifyAll();
                    return null;
                }
                var attentionRun = active;
                finish(attentionRun, TaskRunOutcome.INTERRUPTED, "superseded_by_new_task");
                superseded = new TaskRun.Emission(attentionRun.snapshot(sessionId, sessionIncarnation), attentionRun.ended(sessionId, sessionIncarnation), false);
            }
            var run = queuedRuns.remove(queuedKey);
            run.queued = false;
            active = run;
            TaskRun.touch(run);
            snapshot = run.snapshot(sessionId, sessionIncarnation);
            emission = new TaskRun.Emission(snapshot, null, false);
            lock.notifyAll();
        }
        emit(superseded);
        emit(emission);
        return snapshot;
    }

    public boolean shouldDeferUserBatch(java.util.List<String> turnIds) {
        synchronized (lock) {
            if (active == null || active.terminal()) return false;
            if (active.activeExecutionId != null) return true;
            if (!active.requiredJobs.isEmpty() || !active.continuations.isEmpty()) return true;
            if (active.state() == TaskRunState.DRAINING) return true;
            if (active.state() == TaskRunState.AWAITING_ASYNC) {
                // Only queuedRequests remain; the batch owning this turn must run, not wait forever.
                return active.turnId == null || !turnIds.contains(active.turnId);
            }
            if (active.state() == TaskRunState.NEEDS_ATTENTION) return false;
            if (active.state() == TaskRunState.REGISTERED) {
                if (active.turnId == null || turnIds.isEmpty()) return false;
                return !turnIds.contains(active.turnId);
            }
            return false;
        }
    }

    public void awaitChange(long millis) {
        synchronized (lock) {
            try {
                lock.wait(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void admissionFailed(String reason) {
        emit(change(active -> {
            active.rootDisposition = "failed";
            active.failureReason = reason;
            active.attentionReason = null;
        }));
    }

    public void executionStarted(String executionId, ExecutionKind kind, int claimedRequests) {
        emit(change(run -> {
            run.activeExecutionId = executionId;
            run.activeExecutionKind = kind;
            if (kind == ExecutionKind.ROOT) {
                run.queuedRequests = Math.max(0, run.queuedRequests - Math.max(0, claimedRequests));
            } else if (kind == ExecutionKind.TASK_NOTIFICATION) {
                run.continuations.values().removeIf(value -> value.state() == TaskRun.ContinuationState.QUEUED);
                run.unhandledFailure = false;
                run.failureReason = null;
            }
        }));
    }

    public void executionEnded(String executionId, ExecutionKind kind, TaskRunOutcome outcome, String reason) {
        emit(change(run -> {
            if (run.activeExecutionId != null && !run.activeExecutionId.equals(executionId)) return;
            run.activeExecutionId = null;
            run.activeExecutionKind = null;
            if (kind == ExecutionKind.ROOT) {
                run.rootDisposition = TaskRun.disposition(outcome);
                if (outcome == TaskRunOutcome.FAILED || outcome == TaskRunOutcome.LIMITED) {
                    run.failureReason = reason;
                }
            } else if (outcome == TaskRunOutcome.FAILED || outcome == TaskRunOutcome.CANCELLED
                    || outcome == TaskRunOutcome.LIMITED) {
                run.rootDisposition = TaskRun.disposition(outcome);
                run.failureReason = reason;
            }
        }));
    }

    public void taskLaunched(String jobId, long generation) {
        emit(change(run -> run.requiredJobs.put(TaskRun.jobKey(jobId, generation), generation)));
    }

    /**
     * Must be called before the notification is handed to the queue. When the terminal result still
     * owes a processing turn, the continuation is reserved in the same accounting step as the job
     * settle, so no observer can see "no jobs and no continuation" before the notification exists.
     */
    public void taskSettled(String jobId, long generation, TaskRunOutcome outcome, boolean continuationExpected, String reason) {
        emit(change(run -> {
            run.requiredJobs.remove(TaskRun.jobKey(jobId, generation));
            if (continuationExpected) {
                run.continuations.put(TaskRun.jobKey(jobId, generation),
                        new TaskRun.Continuation(TaskRun.ContinuationState.PENDING, outcome == TaskRunOutcome.FAILED));
                return;
            }
            if (outcome == TaskRunOutcome.FAILED) {
                run.unhandledFailure = true;
                run.failureReason = reason != null ? reason : REASON_JOB_FAILED;
            }
        }));
    }

    public void notificationQueued(String jobId, long generation, boolean accepted) {
        emit(change(run -> {
            var key = TaskRun.jobKey(jobId, generation);
            if (!accepted) {
                run.continuations.remove(key);
                run.attentionReason = "continuation_queue_rejected";
                return;
            }
            var continuation = run.continuations.get(key);
            if (continuation != null) {
                run.continuations.put(key, new TaskRun.Continuation(TaskRun.ContinuationState.QUEUED, continuation.failed()));
            }
        }));
    }

    public void notificationDropped(String jobId, Long generation) {
        emit(change(run -> {
            if (generation != null) {
                run.continuations.remove(TaskRun.jobKey(jobId, generation));
                return;
            }
            run.continuations.keySet().removeIf(key -> key.startsWith(jobId + "#"));
        }));
    }

    public void markUntrackable(String reason) {
        emit(change(run -> {
            run.coverage = COVERAGE_UNTRACKABLE;
            run.attentionReason = reason != null ? reason : REASON_COVERAGE;
        }));
    }

    public void markNeedsAttention(String reason) {
        emit(change(run -> run.attentionReason = reason));
    }

    public void cancelRun(String reason) {
        emit(change(run -> {
            run.rootDisposition = TaskRun.disposition(TaskRunOutcome.CANCELLED);
            run.failureReason = reason;
        }));
    }

    public void limitRun(String reason) {
        emit(change(run -> {
            run.rootDisposition = TaskRun.disposition(TaskRunOutcome.LIMITED);
            run.failureReason = reason;
        }));
    }

    public void pendingWorkChanged() {
        emit(change(run -> { }));
    }

    public void interrupt(String reason) {
        java.util.List<TaskRun.Emission> emissions = new java.util.ArrayList<>();
        synchronized (lock) {
            if (active != null && !active.terminal()) {
                var run = active;
                finish(run, TaskRunOutcome.INTERRUPTED, reason);
                emissions.add(new TaskRun.Emission(run.snapshot(sessionId, sessionIncarnation), run.ended(sessionId, sessionIncarnation), false));
            }
            for (var run : queuedRuns.values()) {
                if (run.terminal()) continue;
                finishQueued(run, TaskRunOutcome.INTERRUPTED, reason);
                emissions.add(new TaskRun.Emission(run.snapshot(sessionId, sessionIncarnation), run.ended(sessionId, sessionIncarnation), false));
            }
            queuedRuns.clear();
            lock.notifyAll();
        }
        emissions.forEach(this::emit);
    }

    public TaskRunSnapshot snapshot() {
        synchronized (lock) {
            return active != null ? active.snapshot(sessionId, sessionIncarnation) : lastEnded != null ? lastEnded.snapshot(sessionId, sessionIncarnation) : null;
        }
    }

    public TaskRunSnapshot activeSnapshot() {
        synchronized (lock) {
            return active != null ? active.snapshot(sessionId, sessionIncarnation) : null;
        }
    }

    public TaskRunSnapshot lastEndedSnapshot() {
        synchronized (lock) {
            return lastEnded != null ? lastEnded.snapshot(sessionId, sessionIncarnation) : null;
        }
    }

    public TaskRunSnapshot snapshotForRun(String taskRunId) {
        if (taskRunId == null) return null;
        synchronized (lock) {
            if (active != null && taskRunId.equals(active.taskRunId)) return active.snapshot(sessionId, sessionIncarnation);
            if (lastEnded != null && taskRunId.equals(lastEnded.taskRunId)) return lastEnded.snapshot(sessionId, sessionIncarnation);
            for (var run : queuedRuns.values()) {
                if (taskRunId.equals(run.taskRunId)) return run.snapshot(sessionId, sessionIncarnation);
            }
            return null;
        }
    }

    public java.util.List<TaskRunSnapshot> queuedSnapshots() {
        synchronized (lock) {
            return queuedRuns.values().stream().map(run -> run.snapshot(sessionId, sessionIncarnation)).toList();
        }
    }

    private TaskRun.Emission change(TaskRun.Change change) {
        TaskRun.Emission emission;
        synchronized (lock) {
            emission = changeLocked(change);
            lock.notifyAll();
        }
        return emission;
    }

    private TaskRun.Emission changeLocked(TaskRun.Change change) {
        if (active == null || active.terminal()) return null;
        var run = active;
        change.apply(run);
        if (run.terminal()) {
            return new TaskRun.Emission(run.snapshot(sessionId, sessionIncarnation), null, false);
        }
        TaskRun.touch(run);
        var ended = evaluate(run);
        return new TaskRun.Emission(run.snapshot(sessionId, sessionIncarnation), ended, false);
    }

    private TaskRunEnded evaluate(TaskRun run) {
        if (run.terminal() || run.activeExecutionId != null || run.attentionReason != null) return null;
        if (!run.requiredJobs.isEmpty() || !run.continuations.isEmpty() || run.queuedRequests > 0) return null;
        if (run.rootDisposition == null) return null;
        if (pendingWorkProbe.getAsBoolean()) return null;
        if (!COVERAGE_COMPLETE.equals(run.coverage)) {
            run.attentionReason = REASON_COVERAGE;
            return null;
        }
        var outcome = switch (run.rootDisposition) {
            case "returned" -> run.unhandledFailure ? TaskRunOutcome.FAILED : TaskRunOutcome.RETURNED;
            case "failed" -> TaskRunOutcome.FAILED;
            case "cancelled" -> TaskRunOutcome.CANCELLED;
            case "limited" -> TaskRunOutcome.LIMITED;
            default -> TaskRunOutcome.INTERRUPTED;
        };
        var reason = switch (outcome) {
            case RETURNED -> REASON_RETURNED;
            case FAILED -> run.failureReason != null ? run.failureReason : REASON_JOB_FAILED;
            case CANCELLED -> "task_run_cancelled";
            case LIMITED -> "task_run_limit_reached";
            case INTERRUPTED -> "task_run_interrupted";
        };
        finish(run, outcome, reason);
        return run.ended(sessionId, sessionIncarnation);
    }

    private void finish(TaskRun run, TaskRunOutcome outcome, String reason) {
        run.outcome = outcome;
        run.reason = reason;
        run.endedAtMs = System.currentTimeMillis();
        run.activeExecutionId = null;
        TaskRun.touch(run);
        lastEnded = run;
        if (run.equals(active)) active = null;
    }

    private void finishQueued(TaskRun run, TaskRunOutcome outcome, String reason) {
        run.outcome = outcome;
        run.reason = reason;
        run.endedAtMs = System.currentTimeMillis();
        run.activeExecutionId = null;
        TaskRun.touch(run);
    }

    private void emit(TaskRun.Emission emission) {
        if (emission == null || emission.snapshot() == null) return;
        var target = observer;
        if (target == null) return;
        if (emission.ended() != null) {
            target.onTaskRunEnded(emission.snapshot(), emission.ended());
        } else if (emission.registered()) {
            target.onTaskRunRegistered(emission.snapshot());
        } else {
            target.onTaskRunChanged(emission.snapshot());
        }
    }

    public enum ExecutionKind {
        ROOT,
        CONTINUATION,
        TASK_NOTIFICATION
    }
}
