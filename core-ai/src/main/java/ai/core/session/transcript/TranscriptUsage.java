package ai.core.session.transcript;

import ai.core.llm.domain.Usage;

/**
 * Token and cost delta of one turn, as stored on a terminal transcript record.
 *
 * @author stephen
 */
public record TranscriptUsage(long inputTokens, long outputTokens, long cachedTokens, double costUsd) {
    public static final TranscriptUsage EMPTY = new TranscriptUsage(0, 0, 0, 0);

    public TranscriptUsage add(TranscriptUsage other) {
        return new TranscriptUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
            cachedTokens + other.cachedTokens, costUsd + other.costUsd);
    }

    public Usage toUsage() {
        var usage = new Usage((int) inputTokens, (int) outputTokens, (int) (inputTokens + outputTokens));
        if (cachedTokens != 0) {
            var details = new Usage.PromptTokensDetails();
            details.cachedTokens = (int) cachedTokens;
            usage.setPromptTokensDetails(details);
        }
        return usage;
    }

    public boolean isEmpty() {
        return inputTokens == 0 && outputTokens == 0 && cachedTokens == 0 && costUsd == 0;
    }
}
