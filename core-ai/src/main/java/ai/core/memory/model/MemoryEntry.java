package ai.core.memory.model;

import ai.core.document.Embedding;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Simple memory entry for long-term memory storage.
 * Uses public fields for core-ng JSON serialization compatibility.
 *
 * @author xander
 */
public class MemoryEntry {

    // Static factory methods
    public static MemoryEntry of(String userId, String content) {
        return new MemoryEntry(userId, content);
    }

    public static MemoryEntry of(String content) {
        return new MemoryEntry(null, content);
    }

    // Public fields for JSON serialization
    public String id;
    public String userId;
    public String content;
    public Embedding embedding;
    public Map<String, Object> metadata;
    public Instant createdAt;
    public Instant lastAccessedAt;

    /**
     * Default constructor for JSON deserialization.
     */
    public MemoryEntry() {
        this.metadata = new HashMap<>();
    }

    public MemoryEntry(String userId, String content) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.content = content;
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.metadata = new HashMap<>();
    }

    public MemoryEntry(String id, String userId, String content, Embedding embedding,
                       Map<String, Object> metadata, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.userId = userId;
        this.content = content;
        this.embedding = embedding;
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.lastAccessedAt = Instant.now();
    }

    // Convenience getters (for code readability)
    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
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

    // Convenience setters
    public void setId(String id) {
        this.id = id;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Record an access to this memory entry.
     */
    public void recordAccess() {
        this.lastAccessedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "MemoryEntry{"
            + "id='" + id + '\''
            + ", userId='" + userId + '\''
            + ", content='" + content + '\''
            + '}';
    }
}
