package ai.core.memory.longterm;

import java.time.Duration;

/**
 * @author xander
 */
public final class MemoryConfig {

    public static Builder builder() {
        return new Builder();
    }

    private boolean enableDecay = true;
    private Duration decayCheckInterval = Duration.ofHours(24);
    private double decayThreshold = 0.1;

    private int defaultTopK = 10;
    private double minSimilarityThreshold = 0.5;

    private int maxBufferTurns = 10;
    private int maxBufferTokens = 2000;
    private boolean extractOnSessionEnd = true;
    private boolean asyncExtraction = true;
    private Duration extractionTimeout = Duration.ofSeconds(30);

    private MemoryConfig() {
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

    public static class Builder {
        private final MemoryConfig config = new MemoryConfig();

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

        public Builder defaultTopK(int topK) {
            config.defaultTopK = topK;
            return this;
        }

        public Builder minSimilarityThreshold(double threshold) {
            config.minSimilarityThreshold = threshold;
            return this;
        }

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

        public MemoryConfig build() {
            return config;
        }
    }
}
