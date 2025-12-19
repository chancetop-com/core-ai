package ai.core.memory.longterm.extraction;

/**
 * Configuration for memory extraction trigger conditions.
 *
 * @author xander
 */
public final class ExtractionTriggerConfig {

    public static Builder builder() {
        return new Builder();
    }

    public static ExtractionTriggerConfig defaultConfig() {
        return new ExtractionTriggerConfig();
    }

    // Batch trigger conditions (any condition met triggers extraction)
    private int maxBufferTurns = 10;
    private int maxBufferTokens = 2000;

    // Session end trigger
    private boolean extractOnSessionEnd = true;

    // Async execution
    private boolean asyncExtraction = true;

    private ExtractionTriggerConfig() {
    }

    // Getters

    public int getMaxBufferTurns() {
        return maxBufferTurns;
    }

    public int getMaxBufferTokens() {
        return maxBufferTokens;
    }

    public boolean isExtractOnSessionEnd() {
        return extractOnSessionEnd;
    }

    public boolean isAsyncExtraction() {
        return asyncExtraction;
    }

    public static class Builder {
        private final ExtractionTriggerConfig config = new ExtractionTriggerConfig();

        public Builder maxBufferTurns(int turns) {
            config.maxBufferTurns = turns;
            return this;
        }

        public Builder maxBufferTokens(int tokens) {
            config.maxBufferTokens = tokens;
            return this;
        }

        public Builder extractOnSessionEnd(boolean extract) {
            config.extractOnSessionEnd = extract;
            return this;
        }

        public Builder asyncExtraction(boolean async) {
            config.asyncExtraction = async;
            return this;
        }

        public ExtractionTriggerConfig build() {
            return config;
        }
    }
}
