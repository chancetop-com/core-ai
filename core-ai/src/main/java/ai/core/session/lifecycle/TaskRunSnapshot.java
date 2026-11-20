package ai.core.session.lifecycle;

/**
 * Read-only projection of one Task Run. The coordinator owns the truth; consumers must not infer a
 * terminal state from counters alone.
 *
 * @author stephen
 */
public record TaskRunSnapshot(
        String sessionId,
        String sessionIncarnation,
        String taskRunId,
        long runGeneration,
        long version,
        String requestId,
        TaskRunState state,
        String rootDisposition,
        String activeExecutionId,
        int requiredJobsUnsettled,
        int pendingContinuations,
        int queuedContinuations,
        int queuedRequests,
        String coverage,
        String attentionReason,
        long startedAtMs,
        Long endedAtMs,
        TaskRunOutcome outcome,
        String reason) {

    public boolean terminal() {
        return state == TaskRunState.TERMINAL;
    }
}
