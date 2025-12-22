package ai.core.memory.longterm;

import java.time.Duration;

/**
 * Configuration for long-term memory storage.
 *
 * @author xander
 */
public final class LongTermMemoryConfig {

    public static Builder builder() {
        return new Builder();
    }

    // Metadata store configuration
    private MetadataStoreType metadataStoreType = MetadataStoreType.IN_MEMORY;
    private String jdbcUrl;
    private String jdbcUsername;
    private String jdbcPassword;

    // Vector store configuration
    private VectorStoreType vectorStoreType = VectorStoreType.IN_MEMORY;
    private String milvusHost;
    private int milvusPort = 19530;
    private String milvusCollection = "long_term_memory";
    private String hnswIndexPath;

    // Embedding configuration
    private int embeddingDimension = 1536;

    // Raw conversation storage
    private boolean enableRawStorage = false;
    private int rawRetentionDays = 90;
    private RawStorageGranularity rawGranularity = RawStorageGranularity.SESSION;

    // Decay configuration
    private boolean enableDecay = true;
    private Duration decayCheckInterval = Duration.ofHours(24);
    private double decayThreshold = 0.1;  // Records below this are candidates for cleanup

    // Search configuration
    private int defaultTopK = 10;
    private double minSimilarityThreshold = 0.5;

    // Extraction trigger configuration
    private int maxBufferTurns = 10;
    private int maxBufferTokens = 2000;
    private boolean extractOnSessionEnd = true;
    private boolean asyncExtraction = true;
    private Duration extractionTimeout = Duration.ofSeconds(30);

    private LongTermMemoryConfig() {
    }

    // Getters

    public MetadataStoreType getMetadataStoreType() {
        return metadataStoreType;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getJdbcUsername() {
        return jdbcUsername;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public VectorStoreType getVectorStoreType() {
        return vectorStoreType;
    }

    public String getMilvusHost() {
        return milvusHost;
    }

    public int getMilvusPort() {
        return milvusPort;
    }

    public String getMilvusCollection() {
        return milvusCollection;
    }

    public String getHnswIndexPath() {
        return hnswIndexPath;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public boolean isEnableRawStorage() {
        return enableRawStorage;
    }

    public int getRawRetentionDays() {
        return rawRetentionDays;
    }

    public RawStorageGranularity getRawGranularity() {
        return rawGranularity;
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

    /**
     * Storage backend type for metadata.
     */
    public enum MetadataStoreType {
        IN_MEMORY,      // For development/testing
        SQLITE,         // Single machine production
        POSTGRESQL      // Distributed production
    }

    /**
     * Storage backend type for vectors.
     */
    public enum VectorStoreType {
        IN_MEMORY,      // For development/testing (uses simple cosine similarity)
        HNSW_LOCAL,     // HNSWLib for single machine
        MILVUS          // Milvus for distributed production
    }

    /**
     * Granularity for raw conversation storage.
     */
    public enum RawStorageGranularity {
        SESSION,        // Store entire session as one record
        TURN,           // Store each turn separately
        EXTRACTION      // Only store turns that produced memories
    }

    public static class Builder {
        private final LongTermMemoryConfig config = new LongTermMemoryConfig();

        // Metadata store

        public Builder metadataStoreType(MetadataStoreType type) {
            config.metadataStoreType = type;
            return this;
        }

        public Builder jdbcUrl(String url) {
            config.jdbcUrl = url;
            return this;
        }

        public Builder jdbcCredentials(String username, String password) {
            config.jdbcUsername = username;
            config.jdbcPassword = password;
            return this;
        }

        // Vector store

        public Builder vectorStoreType(VectorStoreType type) {
            config.vectorStoreType = type;
            return this;
        }

        public Builder milvus(String host, int port, String collection) {
            config.milvusHost = host;
            config.milvusPort = port;
            config.milvusCollection = collection;
            return this;
        }

        public Builder hnswIndexPath(String path) {
            config.hnswIndexPath = path;
            return this;
        }

        public Builder embeddingDimension(int dimension) {
            config.embeddingDimension = dimension;
            return this;
        }

        // Raw storage

        public Builder enableRawStorage(boolean enable) {
            config.enableRawStorage = enable;
            return this;
        }

        public Builder rawRetentionDays(int days) {
            config.rawRetentionDays = days;
            return this;
        }

        public Builder rawGranularity(RawStorageGranularity granularity) {
            config.rawGranularity = granularity;
            return this;
        }

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

        public LongTermMemoryConfig build() {
            return config;
        }
    }
}
