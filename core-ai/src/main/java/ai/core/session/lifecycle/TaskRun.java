package ai.core.session.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
final class TaskRun {
    static String disposition(TaskRunOutcome outcome) {
        return switch (outcome) {
            case RETURNED -> "returned";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
            case LIMITED -> "limited";
            case INTERRUPTED -> "interrupted";
        };
    }

    static String jobKey(String jobId, long generation) {
        return jobId + "#" + generation;
    }

    static void touch(TaskRun run) {
        run.version++;
    }

    final String taskRunId;
    final long generation;
    final String requestId;
    final String turnId;
    final long startedAtMs;
    final Map<String, Long> requiredJobs = new LinkedHashMap<>();
    final Map<String, Continuation> continuations = new LinkedHashMap<>();
    boolean queued;
    long version;
    String rootDisposition;
    String activeExecutionId;
    TaskRunCoordinator.ExecutionKind activeExecutionKind;
    int queuedRequests;
    String coverage = TaskRunCoordinator.COVERAGE_COMPLETE;
    String attentionReason;
    boolean unhandledFailure;
    String failureReason;
    Long endedAtMs;
    TaskRunOutcome outcome;
    String reason;

    TaskRun(String taskRunId, long generation, String requestId, String turnId, boolean queued, long startedAtMs) {
        this.taskRunId = taskRunId;
        this.generation = generation;
        this.requestId = requestId;
        this.turnId = turnId;
        this.queued = queued;
        this.startedAtMs = startedAtMs;
    }

    boolean terminal() {
        return outcome != null;
    }

    int pendingContinuations() {
        return (int) continuations.values().stream().filter(value -> value.state() == ContinuationState.PENDING).count();
    }

    int queuedContinuations() {
        return (int) continuations.values().stream().filter(value -> value.state() == ContinuationState.QUEUED).count();
    }

    TaskRunState state() {
        if (terminal()) return TaskRunState.TERMINAL;
        if (queued) return TaskRunState.QUEUED;
        if (attentionReason != null) return TaskRunState.NEEDS_ATTENTION;
        if (activeExecutionId != null) return TaskRunState.RUNNING;
        if (!requiredJobs.isEmpty() || !continuations.isEmpty() || queuedRequests > 0) return TaskRunState.AWAITING_ASYNC;
        if (rootDisposition != null) return TaskRunState.DRAINING;
        return TaskRunState.REGISTERED;
    }

    TaskRunSnapshot snapshot(String sessionId, String incarnation) {
        return new TaskRunSnapshot(sessionId, incarnation, taskRunId, generation, version, requestId,
                state(), rootDisposition, activeExecutionId, requiredJobs.size(), pendingContinuations(),
                queuedContinuations(), queuedRequests, coverage, attentionReason, startedAtMs, endedAtMs, outcome, reason);
    }

    TaskRunEnded ended(String sessionId, String incarnation) {
        var endedAt = endedAtMs != null ? endedAtMs : System.currentTimeMillis();
        return new TaskRunEnded(sessionId, incarnation, taskRunId, generation, version, outcome, reason, endedAt,
                endedAt - startedAtMs, coverage, "not_applicable");
    }

    enum ContinuationState {
        PENDING,
        QUEUED
    }

    record Continuation(ContinuationState state, boolean failed) {
    }

    record Emission(TaskRunSnapshot snapshot, TaskRunEnded ended, boolean registered) {
    }

    interface Change {
        void apply(TaskRun run);
    }
}
