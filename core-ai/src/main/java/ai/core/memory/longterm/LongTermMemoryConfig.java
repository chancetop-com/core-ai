package ai.core.memory.longterm;

import ai.core.memory.conflict.ConflictStrategy;

import javax.sql.DataSource;

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
    private DataSource dataSource;

    // Vector store configuration
    private VectorStoreType vectorStoreType = VectorStoreType.IN_MEMORY;
    private String milvusHost;
    private int milvusPort = 19530;
    private String milvusCollection = "long_term_memory";
    private String hnswIndexPath;

    // Embedding configuration
    private int embeddingDimension = 1536;

    // Decay configuration
    private boolean enableDecay = true;
    private Duration decayCheckInterval = Duration.ofHours(24);
    private double decayThreshold = 0.1;  // Records below this are candidates for cleanup

    // Search configuration
    private int defaultTopK = 10;
    private Double minSimilarityThreshold = 0.5;

    // Extraction trigger configuration
    // todo convert double to Double ...
    // todo Enmu
    private int maxBufferTurns = 10;
    private int maxBufferTokens = 2000;
    private boolean extractOnSessionEnd = true;
    private boolean asyncExtraction = true;
    private Duration extractionTimeout = Duration.ofSeconds(30);
    //todo private to public?
    // Conflict resolution configuration
    private boolean enableConflictResolution = true;
    private ConflictStrategy conflictStrategy = ConflictStrategy.NEWEST_WITH_MERGE;
    private double conflictSimilarityThreshold = 0.8;

    private LongTermMemoryConfig() {
    }

    public MetadataStoreType getMetadataStoreType() {
        return metadataStoreType;
    }

    public DataSource getDataSource() {
        return dataSource;
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

    public static class Builder {
        private final LongTermMemoryConfig config = new LongTermMemoryConfig();

        // Metadata store - convenience methods

        /**
         * Configure SQLite as metadata store.
         */
        public Builder sqlite(DataSource dataSource) {
            config.metadataStoreType = MetadataStoreType.SQLITE;
            config.dataSource = dataSource;
            return this;
        }

        /**
         * Configure PostgreSQL as metadata store.
         */
        public Builder postgres(DataSource dataSource) {
            config.metadataStoreType = MetadataStoreType.POSTGRESQL;
            config.dataSource = dataSource;
            return this;
        }

        /**
         * Configure in-memory metadata store (default).
         */
        public Builder inMemoryMetadata() {
            config.metadataStoreType = MetadataStoreType.IN_MEMORY;
            config.dataSource = null;
            return this;
        }

        // Vector store - convenience methods

        /**
         * Configure Milvus as vector store.
         */
        public Builder milvus(String host, int port, String collection) {
            config.vectorStoreType = VectorStoreType.MILVUS;
            config.milvusHost = host;
            config.milvusPort = port;
            config.milvusCollection = collection;
            return this;
        }

        /**
         * Configure Milvus with default port and collection.
         */
        public Builder milvus(String host) {
            return milvus(host, 19530, "long_term_memory");
        }

        /**
         * Configure local HNSW as vector store.
         */
        public Builder hnswLocal(String indexPath) {
            config.vectorStoreType = VectorStoreType.HNSW_LOCAL;
            config.hnswIndexPath = indexPath;
            return this;
        }

        /**
         * Configure in-memory vector store (default).
         */
        public Builder inMemoryVector() {
            config.vectorStoreType = VectorStoreType.IN_MEMORY;
            return this;
        }

        // Low-level setters for advanced configuration

        /**
         * Set metadata store type directly. Prefer using sqlite() or postgres() methods.
         */
        public Builder metadataStoreType(MetadataStoreType type) {
            config.metadataStoreType = type;
            return this;
        }

        /**
         * Set vector store type directly. Prefer using milvus() or hnswLocal() methods.
         */
        public Builder vectorStoreType(VectorStoreType type) {
            config.vectorStoreType = type;
            return this;
        }

        /**
         * Set DataSource directly. Usually set via sqlite() or postgres() methods.
         */
        public Builder dataSource(DataSource dataSource) {
            config.dataSource = dataSource;
            return this;
        }

        public Builder embeddingDimension(int dimension) {
            config.embeddingDimension = dimension;
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
