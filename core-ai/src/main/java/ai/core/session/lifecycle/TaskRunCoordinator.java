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
    private static final int CLAIMED_TURN_MEMORY = 64;

    private static boolean claimsQueuedRequest(TaskRun run, java.util.List<String> turnIds) {
        if (run.turnId != null && turnIds.contains(run.turnId)) return true;
        for (var turnId : turnIds) {
            if (turnId != null && run.pendingRequestTurns.contains(turnId)) return true;
        }
        return run.anonymousQueuedRequests > 0 && turnIds.contains(null);
    }

    private final String sessionId;
    private final Object lock = new Object();
    private volatile String sessionIncarnation;
    private volatile TaskRunObserver observer;
    private volatile BooleanSupplier pendingWorkProbe = () -> false;
    private TaskRun active;
    private TaskRun lastEnded;
    private final Map<String, TaskRun> queuedRuns = new LinkedHashMap<>();
    /**
     * Recently claimed turns. A queued marking can arrive after its execution started (even after it
     * ended); such a turn must not be counted or turned into a second queued run.
     */
    private final java.util.Deque<String> claimedTurnMemory = new java.util.ArrayDeque<>();
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
        requestQueued(null, null);
    }

    public void requestQueued(String turnId) {
        requestQueued(turnId, turnId);
    }

    /**
     * Accounts one accepted user input for the active run. The caller marks this after the message is
     * in the command queue, so the driver may already have started (and even finished) the execution
     * that carries it. A request for a turn that execution already claimed must not be counted again;
     * a request accepted after the run executed its root batch is the next queued Task Run.
     */
    public void requestQueued(String requestId, String turnId) {
        TaskRun.Emission emission = null;
        TaskRun.Emission registered = null;
        synchronized (lock) {
            var queued = turnId != null ? queuedRuns.get(turnId) : null;
            if (queued != null) {
                TaskRun.touch(queued);
                emission = new TaskRun.Emission(queued.snapshot(sessionId, sessionIncarnation), null, false);
            } else if (turnId != null && (wasClaimed(turnId)
                    || active != null && active.claimedRootTurns.contains(turnId))) {
                // the execution that carries this request already claimed it
                emission = null;
            } else if (active != null && !active.terminal() && (turnId == null || active.claimedRootTurns.isEmpty())) {
                emission = changeLocked(run -> {
                    if (turnId == null) run.anonymousQueuedRequests++;
                    else run.pendingRequestTurns.add(turnId);
                });
            } else if (turnId != null) {
                var run = registerQueued(requestId, turnId);
                registered = new TaskRun.Emission(run.snapshot(sessionId, sessionIncarnation), null, true);
            }
            lock.notifyAll();
        }
        emit(registered);
        emit(emission);
    }

    /** A follow-up accepted after the active run executed its root batch is the next queued Task Run. */
    private TaskRun registerQueued(String requestId, String turnId) {
        var run = new TaskRun("run-" + UUID.randomUUID(), ++runSequence,
                requestId != null ? requestId : turnId, turnId, true, System.currentTimeMillis());
        queuedRuns.put(turnId, run);
        TaskRun.touch(run);
        return run;
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
        TaskRun.Emission emission = null;
        boolean defer;
        synchronized (lock) {
            if (active == null || active.terminal()) return false;
            if (active.activeExecutionId != null) return true;
            if (!active.requiredJobs.isEmpty() || !active.continuations.isEmpty()) return true;
            if (active.state() == TaskRunState.DRAINING) return true;
            if (active.state() == TaskRunState.AWAITING_ASYNC) {
                // Only queued requests are outstanding. Every queued user input is drained into the
                // same batch, so either this batch carries that request or the accounting can never
                // be claimed (a marking that raced its own execution). Release it in the latter case
                // instead of fencing the driver on a request that no execution will ever claim.
                if (claimsQueuedRequest(active, turnIds)) return false;
                var run = active;
                run.pendingRequestTurns.clear();
                run.anonymousQueuedRequests = 0;
                TaskRun.touch(run);
                var ended = evaluate(run);
                emission = new TaskRun.Emission(run.snapshot(sessionId, sessionIncarnation), ended, false);
                defer = false;
            } else if (active.state() == TaskRunState.NEEDS_ATTENTION) {
                defer = false;
            } else if (active.state() == TaskRunState.REGISTERED) {
                defer = active.turnId != null && !turnIds.isEmpty() && !turnIds.contains(active.turnId);
            } else {
                defer = false;
            }
        }
        emit(emission);
        return defer;
    }

    private boolean wasClaimed(String turnId) {
        if (claimedTurnMemory.contains(turnId)) return true;
        return lastEnded != null && lastEnded.claimedRootTurns.contains(turnId);
    }

    private void rememberClaimed(String turnId) {
        if (claimedTurnMemory.contains(turnId)) return;
        claimedTurnMemory.addLast(turnId);
        while (claimedTurnMemory.size() > CLAIMED_TURN_MEMORY) {
            claimedTurnMemory.removeFirst();
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

    /**
     * @param claimedTurns the turn ids of the user inputs this execution actually carries (null for
     *                     an input without a turn). They are the only pending requests it consumes.
     */
    public void executionStarted(String executionId, ExecutionKind kind, java.util.List<String> claimedTurns) {
        java.util.List<TaskRun.Emission> merged = new java.util.ArrayList<>();
        TaskRun.Emission emission = change(run -> {
            run.activeExecutionId = executionId;
            if (kind == ExecutionKind.ROOT) {
                int anonymous = 0;
                for (var turnId : claimedTurns) {
                    if (turnId == null) {
                        anonymous++;
                        continue;
                    }
                    claimRootTurn(run, turnId, merged);
                }
                if (run.turnId != null) claimRootTurn(run, run.turnId, merged);
                run.anonymousQueuedRequests = Math.max(0, run.anonymousQueuedRequests - anonymous);
            } else if (kind == ExecutionKind.TASK_NOTIFICATION) {
                run.continuations.values().removeIf(value -> value.state() == TaskRun.ContinuationState.QUEUED);
                run.unhandledFailure = false;
                run.failureReason = null;
            }
        });
        merged.forEach(this::emit);
        emit(emission);
    }

    private void claimRootTurn(TaskRun run, String turnId, java.util.List<TaskRun.Emission> merged) {
        run.pendingRequestTurns.remove(turnId);
        run.claimedRootTurns.add(turnId);
        rememberClaimed(turnId);
        var queuedRun = queuedRuns.remove(turnId);
        if (queuedRun != null && !queuedRun.terminal()) {
            finishQueued(queuedRun, TaskRunOutcome.INTERRUPTED, "merged_into_active_run");
            merged.add(new TaskRun.Emission(queuedRun.snapshot(sessionId, sessionIncarnation),
                    queuedRun.ended(sessionId, sessionIncarnation), false));
        }
    }

    public void executionEnded(String executionId, ExecutionKind kind, TaskRunOutcome outcome, String reason) {
        emit(change(run -> {
            if (run.activeExecutionId != null && !run.activeExecutionId.equals(executionId)) return;
            run.activeExecutionId = null;
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

    /**
     * Fences the active run as cancelled and releases its accepted-but-unstarted inputs. The caller
     * drops those inputs from the command queue; keeping them would start a turn nobody asked for
     * after the stop. Returns the turn ids the caller must discard.
     */
    public java.util.List<String> cancelRun(String reason) {
        java.util.List<String> released;
        TaskRun.Emission emission;
        synchronized (lock) {
            if (active == null || active.terminal()) return java.util.List.of();
            var run = active;
            released = new java.util.ArrayList<>(run.pendingRequestTurns);
            emission = changeLocked(target -> {
                target.rootDisposition = TaskRun.disposition(TaskRunOutcome.CANCELLED);
                target.failureReason = reason;
                target.pendingRequestTurns.clear();
                target.anonymousQueuedRequests = 0;
            });
            lock.notifyAll();
        }
        emit(emission);
        return released;
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
        if (run.activeExecutionId != null || run.attentionReason != null || run.terminal()) return null;
        if (!run.requiredJobs.isEmpty() || !run.continuations.isEmpty() || run.queuedRequests() > 0) return null;
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
