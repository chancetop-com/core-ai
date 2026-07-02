package ai.core.llm.domain.responses;

import core.framework.api.json.Property;

public class ResponsesUsage {
    @Property(name = "input_tokens")
    public Integer inputTokens;
    @Property(name = "output_tokens")
    public Integer outputTokens;
    @Property(name = "total_tokens")
    public Integer totalTokens;
    @Property(name = "input_tokens_details")
    public InputTokensDetails inputTokensDetails;
    @Property(name = "output_tokens_details")
    public OutputTokensDetails outputTokensDetails;

    public static class InputTokensDetails {
        @Property(name = "cached_tokens")
        public Integer cachedTokens;
    }

    public static class OutputTokensDetails {
        @Property(name = "reasoning_tokens")
        public Integer reasoningTokens;
    }
}
