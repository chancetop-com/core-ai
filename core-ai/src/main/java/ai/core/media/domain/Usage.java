package ai.core.media.domain;

/**
 * @author stephen
 */
public record Usage(Integer totalTokens, Integer imageCount, Integer videoSeconds,
                    Integer inputTokens, Integer outputTokens,
                    Integer inputTextTokens, Integer inputImageTokens,
                    Double upstreamCostUsd) {
    public Usage(Integer totalTokens, Integer imageCount, Integer videoSeconds) {
        this(totalTokens, imageCount, videoSeconds, null, null, null, null, null);
    }
}
