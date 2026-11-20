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

    private static String disposition(TaskRunOutcome outcome) {
        return switch (outcome) {
            case RETURNED -> "returned";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
            case LIMITED -> "limited";
            case INTERRUPTED -> "interrupted";
        };
    }

    private static String jobKey(String jobId, long generation) {
        return jobId + "#" + generation;
    }

    private static void touch(Run run) {
        run.version++;
    }

    private final String sessionId;
    private final Object lock = new Object();
    private volatile String sessionIncarnation;
    private volatile TaskRunObserver observer;
    private volatile BooleanSupplier pendingWorkProbe = () -> false;
    private Run active;
    private Run lastEnded;
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

    /**
     * The last liveness probe before a run may be called returned. It is a backstop for work that has
     * not reached its own hook yet; the decision still requires every coordinator counter to be clean.
     */
    public void setPendingWorkProbe(BooleanSupplier pendingWorkProbe) {
        this.pendingWorkProbe = pendingWorkProbe != null ? pendingWorkProbe : () -> false;
    }

    public TaskRunSnapshot openRoot(String requestId) {
        Emission emission;
        TaskRunSnapshot snapshot;
        synchronized (lock) {
            if (active == null || active.terminal()) {
                var run = new Run("run-" + UUID.randomUUID(), ++runSequence, requestId, System.currentTimeMillis());
                active = run;
                touch(run);
                snapshot = snapshotOf(run);
                emission = new Emission(snapshot, null, true);
            } else {
                snapshot = snapshotOf(active);
                emission = null;
            }
        }
        emit(emission);
        return snapshot;
    }

    public void requestQueued() {
        emit(change(active -> active.queuedRequests++));
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
                run.continuations.values().removeIf(value -> value.state() == ContinuationState.QUEUED);
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
                run.rootDisposition = disposition(outcome);
                if (outcome == TaskRunOutcome.FAILED || outcome == TaskRunOutcome.LIMITED) {
                    run.failureReason = reason;
                }
            } else if (outcome == TaskRunOutcome.FAILED || outcome == TaskRunOutcome.CANCELLED
                    || outcome == TaskRunOutcome.LIMITED) {
                run.rootDisposition = disposition(outcome);
                run.failureReason = reason;
            }
        }));
    }

    public void taskLaunched(String jobId, long generation) {
        emit(change(run -> run.requiredJobs.put(jobKey(jobId, generation), generation)));
    }

    /**
     * Must be called before the notification is handed to the queue. When the terminal result still
     * owes a processing turn, the continuation is reserved in the same accounting step as the job
     * settle, so no observer can see "no jobs and no continuation" before the notification exists.
     */
    public void taskSettled(String jobId, long generation, TaskRunOutcome outcome, boolean continuationExpected, String reason) {
        emit(change(run -> {
            run.requiredJobs.remove(jobKey(jobId, generation));
            if (continuationExpected) {
                run.continuations.put(jobKey(jobId, generation),
                        new Continuation(ContinuationState.PENDING, outcome == TaskRunOutcome.FAILED));
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
            var key = jobKey(jobId, generation);
            if (!accepted) {
                run.continuations.remove(key);
                run.attentionReason = "continuation_queue_rejected";
                return;
            }
            var continuation = run.continuations.get(key);
            if (continuation != null) {
                run.continuations.put(key, new Continuation(ContinuationState.QUEUED, continuation.failed()));
            }
        }));
    }

    public void notificationDropped(String jobId, Long generation) {
        emit(change(run -> {
            if (generation != null) {
                run.continuations.remove(jobKey(jobId, generation));
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
            run.rootDisposition = disposition(TaskRunOutcome.CANCELLED);
            run.failureReason = reason;
        }));
    }

    public void limitRun(String reason) {
        emit(change(run -> {
            run.rootDisposition = disposition(TaskRunOutcome.LIMITED);
            run.failureReason = reason;
        }));
    }

    public void pendingWorkChanged() {
        emit(change(run -> { }));
    }

    public void interrupt(String reason) {
        Emission emission;
        synchronized (lock) {
            if (active == null || active.terminal()) return;
            var run = active;
            finish(run, TaskRunOutcome.INTERRUPTED, reason);
            emission = new Emission(snapshotOf(run), endedOf(run), false);
        }
        emit(emission);
    }

    public TaskRunSnapshot snapshot() {
        synchronized (lock) {
            return active != null ? snapshotOf(active) : lastEnded != null ? snapshotOf(lastEnded) : null;
        }
    }

    public TaskRunSnapshot activeSnapshot() {
        synchronized (lock) {
            return active != null ? snapshotOf(active) : null;
        }
    }

    public TaskRunSnapshot lastEndedSnapshot() {
        synchronized (lock) {
            return lastEnded != null ? snapshotOf(lastEnded) : null;
        }
    }

    private Emission change(Change change) {
        Emission emission;
        synchronized (lock) {
            if (active == null || active.terminal()) return null;
            var run = active;
            change.apply(run);
            if (run.terminal()) {
                emission = new Emission(snapshotOf(run), null, false);
            } else {
                touch(run);
                var ended = evaluate(run);
                emission = new Emission(snapshotOf(run), ended, false);
            }
        }
        return emission;
    }

    private TaskRunEnded evaluate(Run run) {
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
        return endedOf(run);
    }

    private void finish(Run run, TaskRunOutcome outcome, String reason) {
        run.outcome = outcome;
        run.reason = reason;
        run.endedAtMs = System.currentTimeMillis();
        run.activeExecutionId = null;
        touch(run);
        lastEnded = run;
        if (active == run) active = null;
    }

    private TaskRunSnapshot snapshotOf(Run run) {
        return new TaskRunSnapshot(sessionId, sessionIncarnation, run.taskRunId, run.generation, run.version,
                run.requestId, run.state(), run.rootDisposition, run.activeExecutionId, run.requiredJobs.size(),
                run.pendingContinuations(), run.queuedContinuations(), run.queuedRequests, run.coverage,
                run.attentionReason, run.startedAtMs, run.endedAtMs, run.outcome, run.reason);
    }

    private TaskRunEnded endedOf(Run run) {
        var endedAt = run.endedAtMs != null ? run.endedAtMs : System.currentTimeMillis();
        return new TaskRunEnded(sessionId, sessionIncarnation, run.taskRunId, run.generation, run.version,
                run.outcome, run.reason, endedAt, endedAt - run.startedAtMs, run.coverage, "not_applicable");
    }

    private void emit(Emission emission) {
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

    private enum ContinuationState {
        PENDING,
        QUEUED
    }

    private record Continuation(ContinuationState state, boolean failed) {
    }

    private record Emission(TaskRunSnapshot snapshot, TaskRunEnded ended, boolean registered) {
    }

    private interface Change {
        void apply(Run run);
    }

    private static final class Run {
        private final String taskRunId;
        private final long generation;
        private final String requestId;
        private final long startedAtMs;
        private final Map<String, Long> requiredJobs = new LinkedHashMap<>();
        private final Map<String, Continuation> continuations = new LinkedHashMap<>();
        private long version;
        private String rootDisposition;
        private String activeExecutionId;
        private ExecutionKind activeExecutionKind;
        private int queuedRequests;
        private String coverage = COVERAGE_COMPLETE;
        private String attentionReason;
        private boolean unhandledFailure;
        private String failureReason;
        private Long endedAtMs;
        private TaskRunOutcome outcome;
        private String reason;

        private Run(String taskRunId, long generation, String requestId, long startedAtMs) {
            this.taskRunId = taskRunId;
            this.generation = generation;
            this.requestId = requestId;
            this.startedAtMs = startedAtMs;
        }

        private boolean terminal() {
            return outcome != null;
        }

        private int pendingContinuations() {
            return (int) continuations.values().stream().filter(value -> value.state() == ContinuationState.PENDING).count();
        }

        private int queuedContinuations() {
            return (int) continuations.values().stream().filter(value -> value.state() == ContinuationState.QUEUED).count();
        }

        private TaskRunState state() {
            if (terminal()) return TaskRunState.TERMINAL;
            if (attentionReason != null) return TaskRunState.NEEDS_ATTENTION;
            if (activeExecutionId != null) return TaskRunState.RUNNING;
            if (!requiredJobs.isEmpty() || !continuations.isEmpty() || queuedRequests > 0) return TaskRunState.AWAITING_ASYNC;
            if (rootDisposition != null) return TaskRunState.DRAINING;
            return TaskRunState.REGISTERED;
        }
    }
}
