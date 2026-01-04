package ai.core.memory.longterm;

import ai.core.memory.conflict.ConflictStrategy;

import java.time.Duration;

/**
 * @author xander
 */
public final class LongTermMemoryConfig {

    public static Builder builder() {
        return new Builder();
    }

    // Decay configuration
    private boolean enableDecay = true;
    private Duration decayCheckInterval = Duration.ofHours(24);
    private double decayThreshold = 0.1;

    // Search configuration
    private int defaultTopK = 10;
    private double minSimilarityThreshold = 0.5;

    // Extraction trigger configuration
    private int maxBufferTurns = 10;
    private int maxBufferTokens = 2000;
    private boolean extractOnSessionEnd = true;
    private boolean asyncExtraction = true;
    private Duration extractionTimeout = Duration.ofSeconds(30);

    // Conflict resolution configuration
    private boolean enableConflictResolution = true;
    private ConflictStrategy conflictStrategy = ConflictStrategy.LLM_MERGE;
    private double conflictSimilarityThreshold = 0.8;

    private LongTermMemoryConfig() {
    }

    public boolean isEnableDecay() {
        return enableDecay;
    }

    public Duration getDecayCheckInterval() {
        return decayCheckInterval;
    }

    public double getDecayThreshold() {
        return decayThreshold;
    }

    public int getDefaultTopK() {
        return defaultTopK;
    }

    public double getMinSimilarityThreshold() {
        return minSimilarityThreshold;
    }

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

    public Duration getExtractionTimeout() {
        return extractionTimeout;
    }

    public boolean isEnableConflictResolution() {
        return enableConflictResolution;
    }

    public ConflictStrategy getConflictStrategy() {
        return conflictStrategy;
    }

    public double getConflictSimilarityThreshold() {
        return conflictSimilarityThreshold;
    }

    public static class Builder {
        private final LongTermMemoryConfig config = new LongTermMemoryConfig();

        // Decay

        public Builder enableDecay(boolean enable) {
            config.enableDecay = enable;
            return this;
        }

        public Builder decayCheckInterval(Duration interval) {
            config.decayCheckInterval = interval;
            return this;
        }

        public Builder decayThreshold(double threshold) {
            config.decayThreshold = threshold;
            return this;
        }

        // Search

        public Builder defaultTopK(int topK) {
            config.defaultTopK = topK;
            return this;
        }

        public Builder minSimilarityThreshold(double threshold) {
            config.minSimilarityThreshold = threshold;
            return this;
        }

        // Extraction trigger

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

        public Builder extractionTimeout(Duration timeout) {
            config.extractionTimeout = timeout;
            return this;
        }

        // Conflict resolution

        public Builder enableConflictResolution(boolean enable) {
            config.enableConflictResolution = enable;
            return this;
        }

        public Builder conflictStrategy(ConflictStrategy strategy) {
            config.conflictStrategy = strategy;
            return this;
        }

        public Builder conflictSimilarityThreshold(double threshold) {
            config.conflictSimilarityThreshold = threshold;
            return this;
        }

        public LongTermMemoryConfig build() {
            return config;
        }
    }
}
