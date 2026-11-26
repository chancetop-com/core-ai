package ai.core.session.lifecycle;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    /**
     * Requests accepted before this run's root execution started. The driver drains every queued user
     * input into the same batch, so they are claimed by that execution; they only keep the run open
     * until then. A request for another turn is a queued Task Run of its own and never lands here.
     */
    final Set<String> pendingRequestTurns = new LinkedHashSet<>();
    /**
     * Turns already claimed by a root execution. A request marked queued after this point was claimed
     * before the send path could account it, so it must not be counted again.
     */
    final Set<String> claimedRootTurns = new LinkedHashSet<>();
    int anonymousQueuedRequests;
    boolean queued;
    long version;
    String rootDisposition;
    String activeExecutionId;
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

    int queuedRequests() {
        return pendingRequestTurns.size() + anonymousQueuedRequests;
    }

    TaskRunState state() {
        if (terminal()) return TaskRunState.TERMINAL;
        if (queued) return TaskRunState.QUEUED;
        if (attentionReason != null) return TaskRunState.NEEDS_ATTENTION;
        if (activeExecutionId != null) return TaskRunState.RUNNING;
        if (!requiredJobs.isEmpty() || !continuations.isEmpty() || queuedRequests() > 0) return TaskRunState.AWAITING_ASYNC;
        if (rootDisposition != null) return TaskRunState.DRAINING;
        return TaskRunState.REGISTERED;
    }

    TaskRunSnapshot snapshot(String sessionId, String incarnation) {
        return new TaskRunSnapshot(sessionId, incarnation, taskRunId, generation, version, requestId,
                state(), rootDisposition, activeExecutionId, requiredJobs.size(), pendingContinuations(),
                queuedContinuations(), queuedRequests(), coverage, attentionReason, startedAtMs, endedAtMs, outcome, reason);
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
