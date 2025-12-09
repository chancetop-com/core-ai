package ai.core.memory;

import ai.core.memory.decay.ExponentialDecayPolicy;
import ai.core.memory.decay.MemoryDecayPolicy;
import ai.core.memory.store.GraphMemoryStore;
import ai.core.memory.store.KeyValueMemoryStore;
import ai.core.memory.store.VectorMemoryStore;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for long-term memory system.
 *
 * @author xander
 */
public class MemoryConfig {
    public static Builder builder() {
        return new Builder();
    }

    // Retrieval trigger mode
    private RetrievalTriggerMode triggerMode = RetrievalTriggerMode.HYBRID;

    // Layer 1: Auto retrieval config
    private int autoRetrievalTopK = 3;
    private Duration autoRetrievalTimeout = Duration.ofMillis(50);
    private boolean autoRetrievalFastMode = true;

    // Layer 2: Tool retrieval config
    private int toolRetrievalTopK = 10;
    private boolean toolRetrievalEnableGraph = false;

    // Similarity search config
    private double similarityThreshold = 0.7;
    private int semanticMemoryTopK = 5;
    private int episodicMemoryTopK = 3;

    // Decay config
    private MemoryDecayPolicy decayPolicy = new ExponentialDecayPolicy();
    private double decayRate = 0.1;
    private double minStrength = 0.1;
    private Duration decayInterval = Duration.ofDays(1);

    // Conditional trigger keywords (for CONDITIONAL mode)
    private List<String> triggerKeywords = List.of(
        "之前", "上次", "记得", "以前", "曾经",
        "我说过", "你知道", "我的", "历史",
        "remember", "before", "previously", "last time", "my"
    );
    private int minQueryLengthForSkip = 50;

    // Memory extraction config
    private boolean enableMemoryExtraction = true;
    private boolean asyncExtraction = true;

    // Storage backends
    private VectorMemoryStore vectorStore;
    private KeyValueMemoryStore kvStore;
    private GraphMemoryStore graphStore;

    // Embedding config
    private String embeddingModel = "text-embedding-3-small";

    // Getters
    public RetrievalTriggerMode getTriggerMode() {
        return triggerMode;
    }

    public int getAutoRetrievalTopK() {
        return autoRetrievalTopK;
    }

    public Duration getAutoRetrievalTimeout() {
        return autoRetrievalTimeout;
    }

    public boolean isAutoRetrievalFastMode() {
        return autoRetrievalFastMode;
    }

    public int getToolRetrievalTopK() {
        return toolRetrievalTopK;
    }

    public boolean isToolRetrievalEnableGraph() {
        return toolRetrievalEnableGraph;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public int getSemanticMemoryTopK() {
        return semanticMemoryTopK;
    }

    public int getEpisodicMemoryTopK() {
        return episodicMemoryTopK;
    }

    public MemoryDecayPolicy getDecayPolicy() {
        return decayPolicy;
    }

    public double getDecayRate() {
        return decayRate;
    }

    public double getMinStrength() {
        return minStrength;
    }

    public Duration getDecayInterval() {
        return decayInterval;
    }

    public List<String> getTriggerKeywords() {
        return triggerKeywords;
    }

    public int getMinQueryLengthForSkip() {
        return minQueryLengthForSkip;
    }

    public boolean isEnableMemoryExtraction() {
        return enableMemoryExtraction;
    }

    public boolean isAsyncExtraction() {
        return asyncExtraction;
    }

    public VectorMemoryStore getVectorStore() {
        return vectorStore;
    }

    public KeyValueMemoryStore getKvStore() {
        return kvStore;
    }

    public GraphMemoryStore getGraphStore() {
        return graphStore;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    /**
     * Retrieval trigger mode enumeration.
     */
    public enum RetrievalTriggerMode {
        /**
         * Always retrieve on every query.
         */
        AUTO,

        /**
         * Retrieve based on keyword/pattern matching.
         */
        CONDITIONAL,

        /**
         * Only retrieve via tool calls.
         */
        TOOL_ONLY,

        /**
         * Lightweight auto + deep tool retrieval (recommended).
         */
        HYBRID
    }

    /**
     * Builder for MemoryConfig.
     */
    public static class Builder {
        private RetrievalTriggerMode triggerMode = RetrievalTriggerMode.HYBRID;
        private int autoRetrievalTopK = 3;
        private Duration autoRetrievalTimeout = Duration.ofMillis(50);
        private boolean autoRetrievalFastMode = true;
        private int toolRetrievalTopK = 10;
        private boolean toolRetrievalEnableGraph = false;
        private double similarityThreshold = 0.7;
        private int semanticMemoryTopK = 5;
        private int episodicMemoryTopK = 3;
        private MemoryDecayPolicy decayPolicy = new ExponentialDecayPolicy();
        private double decayRate = 0.1;
        private double minStrength = 0.1;
        private Duration decayInterval = Duration.ofDays(1);
        private List<String> triggerKeywords = List.of(
            "之前", "上次", "记得", "以前", "曾经",
            "我说过", "你知道", "我的", "历史",
            "remember", "before", "previously", "last time", "my"
        );
        private int minQueryLengthForSkip = 50;
        private boolean enableMemoryExtraction = true;
        private boolean asyncExtraction = true;
        private VectorMemoryStore vectorStore;
        private KeyValueMemoryStore kvStore;
        private GraphMemoryStore graphStore;
        private String embeddingModel = "text-embedding-3-small";

        public Builder triggerMode(RetrievalTriggerMode mode) {
            this.triggerMode = mode;
            return this;
        }

        public Builder autoRetrievalTopK(int topK) {
            this.autoRetrievalTopK = topK;
            return this;
        }

        public Builder autoRetrievalTimeout(Duration timeout) {
            this.autoRetrievalTimeout = timeout;
            return this;
        }

        public Builder autoRetrievalFastMode(boolean fastMode) {
            this.autoRetrievalFastMode = fastMode;
            return this;
        }

        public Builder toolRetrievalTopK(int topK) {
            this.toolRetrievalTopK = topK;
            return this;
        }

        public Builder toolRetrievalEnableGraph(boolean enable) {
            this.toolRetrievalEnableGraph = enable;
            return this;
        }

        public Builder similarityThreshold(double threshold) {
            this.similarityThreshold = threshold;
            return this;
        }

        public Builder semanticMemoryTopK(int topK) {
            this.semanticMemoryTopK = topK;
            return this;
        }

        public Builder episodicMemoryTopK(int topK) {
            this.episodicMemoryTopK = topK;
            return this;
        }

        public Builder decayPolicy(MemoryDecayPolicy policy) {
            this.decayPolicy = policy;
            return this;
        }

        public Builder decayRate(double rate) {
            this.decayRate = rate;
            return this;
        }

        public Builder minStrength(double minStrength) {
            this.minStrength = minStrength;
            return this;
        }

        public Builder decayInterval(Duration interval) {
            this.decayInterval = interval;
            return this;
        }

        public Builder triggerKeywords(List<String> keywords) {
            this.triggerKeywords = keywords;
            return this;
        }

        public Builder minQueryLengthForSkip(int length) {
            this.minQueryLengthForSkip = length;
            return this;
        }

        public Builder enableMemoryExtraction(boolean enable) {
            this.enableMemoryExtraction = enable;
            return this;
        }

        public Builder asyncExtraction(boolean async) {
            this.asyncExtraction = async;
            return this;
        }

        public Builder vectorStore(VectorMemoryStore store) {
            this.vectorStore = store;
            return this;
        }

        public Builder kvStore(KeyValueMemoryStore store) {
            this.kvStore = store;
            return this;
        }

        public Builder graphStore(GraphMemoryStore store) {
            this.graphStore = store;
            return this;
        }

        public Builder embeddingModel(String model) {
            this.embeddingModel = model;
            return this;
        }

        public MemoryConfig build() {
            var config = new MemoryConfig();
            config.triggerMode = this.triggerMode;
            config.autoRetrievalTopK = this.autoRetrievalTopK;
            config.autoRetrievalTimeout = this.autoRetrievalTimeout;
            config.autoRetrievalFastMode = this.autoRetrievalFastMode;
            config.toolRetrievalTopK = this.toolRetrievalTopK;
            config.toolRetrievalEnableGraph = this.toolRetrievalEnableGraph;
            config.similarityThreshold = this.similarityThreshold;
            config.semanticMemoryTopK = this.semanticMemoryTopK;
            config.episodicMemoryTopK = this.episodicMemoryTopK;
            config.decayPolicy = this.decayPolicy;
            config.decayRate = this.decayRate;
            config.minStrength = this.minStrength;
            config.decayInterval = this.decayInterval;
            config.triggerKeywords = this.triggerKeywords;
            config.minQueryLengthForSkip = this.minQueryLengthForSkip;
            config.enableMemoryExtraction = this.enableMemoryExtraction;
            config.asyncExtraction = this.asyncExtraction;
            config.vectorStore = this.vectorStore;
            config.kvStore = this.kvStore;
            config.graphStore = this.graphStore;
            config.embeddingModel = this.embeddingModel;
            return config;
        }
    }
}
