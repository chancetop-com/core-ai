package ai.core.context;

/**
 * Snapshot of one compression attempt: how much of the model context the conversation occupied when
 * it started, and what happened to it. {@code afterCount} counts the messages being summarized while
 * the phase is {@code STARTED}, the messages left in the conversation once it is {@code COMPLETED},
 * and the untouched conversation when it is {@code SKIPPED}.
 *
 * @author stephen
 */
public record CompressionReport(Phase phase, int beforeCount, int afterCount, int contextTokens,
                                int maxContextTokens, double triggerThreshold, String reason) {
    /**
     * @return true when the conversation was actually summarized and replaced
     */
    public boolean completed() {
        return phase == Phase.COMPLETED;
    }

    /**
     * @return the share of the model context window the conversation occupied, in percent
     */
    public int usedPercent() {
        return maxContextTokens > 0 ? (int) Math.round(contextTokens * 100.0 / maxContextTokens) : 0;
    }

    /**
     * @return the share of the context window that triggers compression, in percent
     */
    public int thresholdPercent() {
        return (int) Math.round(triggerThreshold * 100);
    }

    public enum Phase {
        STARTED, COMPLETED, SKIPPED
    }
}
