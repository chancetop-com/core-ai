package ai.core.session.lifecycle;

/**
 * @author stephen
 */
public record TaskRunEnded(
        String sessionId,
        String sessionIncarnation,
        String taskRunId,
        long runGeneration,
        long version,
        TaskRunOutcome outcome,
        String reason,
        long endedAtMs,
        long durationMs,
        String coverage,
        String externalStopState) {
}
