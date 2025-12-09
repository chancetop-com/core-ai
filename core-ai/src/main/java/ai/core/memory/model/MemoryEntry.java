package ai.core.memory.model;

import ai.core.document.Embedding;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for memory entries.
 *
 * @author xander
 */
public class MemoryEntry {
    public static Builder<?> builder() {
        return new Builder<>();
    }

    protected String id;
    protected String userId;
    protected String agentId;
    protected String content;
    protected MemoryType type;
    protected Embedding embedding;
    protected Map<String, Object> metadata;

    // Time and access tracking
    protected Instant createdAt;
    protected Instant lastAccessedAt;
    protected int accessCount;

    // Decay related
    protected double importance;
    protected double strength;

    protected MemoryEntry() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.accessCount = 0;
        this.importance = 0.5;
        this.strength = 1.0;
        this.metadata = new HashMap<>();
    }

    protected MemoryEntry(Builder<?> builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.userId = builder.userId;
        this.agentId = builder.agentId;
        this.content = builder.content;
        this.type = builder.type;
        this.embedding = builder.embedding;
        this.metadata = builder.metadata != null ? builder.metadata : new HashMap<>();
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.lastAccessedAt = builder.lastAccessedAt != null ? builder.lastAccessedAt : Instant.now();
        this.accessCount = builder.accessCount;
        this.importance = builder.importance;
        this.strength = builder.strength;
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getContent() {
        return content;
    }

    public MemoryType getType() {
        return type;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public double getImportance() {
        return importance;
    }

    public double getStrength() {
        return strength;
    }

    // Setters for mutable fields
    public void setId(String id) {
        this.id = id;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }

    public void setStrength(double strength) {
        this.strength = strength;
    }

    /**
     * Record an access to this memory entry.
     */
    public void recordAccess() {
        this.lastAccessedAt = Instant.now();
        this.accessCount++;
    }

    /**
     * Builder for MemoryEntry.
     */
    public static class Builder<T extends Builder<T>> {
        protected String id;
        protected String userId;
        protected String agentId;
        protected String content;
        protected MemoryType type;
        protected Embedding embedding;
        protected Map<String, Object> metadata;
        protected Instant createdAt;
        protected Instant lastAccessedAt;
        protected int accessCount = 0;
        protected double importance = 0.5;
        protected double strength = 1.0;

        @SuppressWarnings("unchecked")
        protected T self() {
            return (T) this;
        }

        public T id(String id) {
            this.id = id;
            return self();
        }

        public T userId(String userId) {
            this.userId = userId;
            return self();
        }

        public T agentId(String agentId) {
            this.agentId = agentId;
            return self();
        }

        public T content(String content) {
            this.content = content;
            return self();
        }

        public T type(MemoryType type) {
            this.type = type;
            return self();
        }

        public T embedding(Embedding embedding) {
            this.embedding = embedding;
            return self();
        }

        public T metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return self();
        }

        public T createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return self();
        }

        public T lastAccessedAt(Instant lastAccessedAt) {
            this.lastAccessedAt = lastAccessedAt;
            return self();
        }

        public T accessCount(int accessCount) {
            this.accessCount = accessCount;
            return self();
        }

        public T importance(double importance) {
            this.importance = importance;
            return self();
        }

        public T strength(double strength) {
            this.strength = strength;
            return self();
        }

        public MemoryEntry build() {
            return new MemoryEntry(this);
        }
    }
}
