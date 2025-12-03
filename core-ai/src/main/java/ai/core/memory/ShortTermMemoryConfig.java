package ai.core.memory;

/**
 * Configuration for short-term memory.
 *
 * @author stephen
 */
public final class ShortTermMemoryConfig {
    public static Builder builder() {
        return new Builder();
    }

    public static ShortTermMemoryConfig defaultConfig() {
        return new ShortTermMemoryConfig(new Builder());
    }

    private final int maxMessages;
    private final int maxTokens;
    private final boolean enableRollingSummary;
    private final String rollingSummaryPrompt;

    private ShortTermMemoryConfig(Builder builder) {
        this.maxMessages = builder.maxMessages;
        this.maxTokens = builder.maxTokens;
        this.enableRollingSummary = builder.enableRollingSummary;
        this.rollingSummaryPrompt = builder.rollingSummaryPrompt;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public boolean isEnableRollingSummary() {
        return enableRollingSummary;
    }

    public String getRollingSummaryPrompt() {
        return rollingSummaryPrompt;
    }

    public static class Builder {
        private int maxMessages = 20;
        private int maxTokens = 4000;
        private boolean enableRollingSummary = true;
        private String rollingSummaryPrompt = """
            Summarize the following conversation history concisely, \
            preserving key information, decisions, and context that may be needed later.
            Keep the summary under 200 words.

            Conversation:
            {{conversation}}
            """;

        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder enableRollingSummary(boolean enable) {
            this.enableRollingSummary = enable;
            return this;
        }

        public Builder rollingSummaryPrompt(String prompt) {
            this.rollingSummaryPrompt = prompt;
            return this;
        }

        public ShortTermMemoryConfig build() {
            return new ShortTermMemoryConfig(this);
        }
    }
}
