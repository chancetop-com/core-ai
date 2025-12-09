package ai.core.memory.model;

import java.time.Duration;

/**
 * Options for memory retrieval operations.
 *
 * @author xander
 */
public class RetrievalOptions {
    public static RetrievalOptions defaults() {
        return new RetrievalOptions();
    }

    /**
     * Create fast retrieval options for Layer 1 auto-retrieval.
     *
     * @param topK    max results
     * @param timeout timeout duration
     * @return fast retrieval options
     */
    public static RetrievalOptions fast(int topK, Duration timeout) {
        var options = new RetrievalOptions();
        options.topK = topK;
        options.timeout = timeout;
        options.fastMode = true;
        options.enableGraphSearch = false;
        return options;
    }

    /**
     * Create deep retrieval options for Layer 2 tool-based retrieval.
     *
     * @param topK max results
     * @return deep retrieval options
     */
    public static RetrievalOptions deep(int topK) {
        var options = new RetrievalOptions();
        options.topK = topK;
        options.fastMode = false;
        options.enableGraphSearch = true;
        return options;
    }

    private int topK = 5;
    private double similarityThreshold = 0.7;
    private boolean fastMode = false;
    private Duration timeout = Duration.ofMillis(100);
    private boolean enableGraphSearch = false;
    private int graphDepth = 2;
    private MemoryFilter filter;

    public RetrievalOptions withTopK(int topK) {
        this.topK = topK;
        return this;
    }

    public RetrievalOptions withSimilarityThreshold(double threshold) {
        this.similarityThreshold = threshold;
        return this;
    }

    public RetrievalOptions withFastMode(boolean fastMode) {
        this.fastMode = fastMode;
        return this;
    }

    public RetrievalOptions withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public RetrievalOptions withGraphSearch(boolean enable, int depth) {
        this.enableGraphSearch = enable;
        this.graphDepth = depth;
        return this;
    }

    public RetrievalOptions withFilter(MemoryFilter filter) {
        this.filter = filter;
        return this;
    }

    // Getters
    public int getTopK() {
        return topK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public boolean isFastMode() {
        return fastMode;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public boolean isEnableGraphSearch() {
        return enableGraphSearch;
    }

    public int getGraphDepth() {
        return graphDepth;
    }

    public MemoryFilter getFilter() {
        return filter;
    }
}
