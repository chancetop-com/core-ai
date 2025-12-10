package ai.core.memory.model;

import ai.core.document.Embedding;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Simple memory entry for long-term memory storage.
 *
 * @author xander
 */
public class MemoryEntry {
    private String id;
    private String userId;
    private String content;
    private Embedding embedding;
    private Map<String, Object> metadata;
    private final Instant createdAt;
    private Instant lastAccessedAt;

    public MemoryEntry() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.metadata = new HashMap<>();
    }

    public MemoryEntry(String userId, String content) {
        this();
        this.userId = userId;
        this.content = content;
    }

    public MemoryEntry(String id, String userId, String content, Embedding embedding, Map<String, Object> metadata, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.userId = userId;
        this.content = content;
        this.embedding = embedding;
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.lastAccessedAt = Instant.now();
    }

    // Static factory methods
    public static MemoryEntry of(String userId, String content) {
        return new MemoryEntry(userId, content);
    }

    public static MemoryEntry of(String content) {
        return new MemoryEntry(null, content);
    }

    // Getters
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

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * Record an access to this memory entry.
     */
    public void recordAccess() {
        this.lastAccessedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "MemoryEntry{" +
            "id='" + id + '\'' +
            ", userId='" + userId + '\'' +
            ", content='" + content + '\'' +
            '}';
    }
}
