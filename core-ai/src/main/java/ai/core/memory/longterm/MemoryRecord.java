package ai.core.memory.longterm;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a long-term memory record.
 * Embedding is stored separately in VectorStore, linked by id.
 *
 * @author xander
 */
public class MemoryRecord {

    private static final double FREQUENCY_BONUS_FACTOR = 0.1;
    private static final double DEFAULT_IMPORTANCE = 0.5;
    private static final double DEFAULT_DECAY_FACTOR = 1.0;

    public static Builder builder() {
        return new Builder();
    }

    private String id;
    private MemoryScope scope;
    private String content;

    // Weight and decay
    private double importance;
    private final AtomicInteger accessCount = new AtomicInteger(0);
    private volatile double decayFactor;

    // Timestamps
    private Instant createdAt;
    private Instant lastAccessedAt;

    // Extension
    private Map<String, Object> metadata;

    public MemoryRecord() {
        this.id = UUID.randomUUID().toString();
        this.importance = DEFAULT_IMPORTANCE;
        this.decayFactor = DEFAULT_DECAY_FACTOR;
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.metadata = new HashMap<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MemoryScope getScope() {
        return scope;
    }

    public void setScope(MemoryScope scope) {
        this.scope = scope;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = importance;
    }

    public int getAccessCount() {
        return accessCount.get();
    }

    public void setAccessCount(int accessCount) {
        this.accessCount.set(accessCount);
    }

    public void incrementAccessCount() {
        this.accessCount.incrementAndGet();
        this.lastAccessedAt = Instant.now();
    }

    public double getDecayFactor() {
        return decayFactor;
    }

    public void setDecayFactor(double decayFactor) {
        this.decayFactor = decayFactor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    public double calculateEffectiveScore(double similarity) {
        double frequencyBonus = 1.0 + FREQUENCY_BONUS_FACTOR * Math.log1p(accessCount.get());
        return similarity * importance * decayFactor * frequencyBonus;
    }

    public static class Builder {
        private String id;
        private MemoryScope scope;
        private String content;
        private Double importance;
        private final Map<String, Object> metadata = new HashMap<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder scope(MemoryScope scope) {
            this.scope = scope;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder importance(double importance) {
            this.importance = importance;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public MemoryRecord build() {
            MemoryRecord record = new MemoryRecord();
            if (id != null) record.setId(id);
            if (scope != null) record.setScope(scope);
            if (content != null) record.setContent(content);
            if (importance != null) record.setImportance(importance);
            if (!metadata.isEmpty()) record.setMetadata(new HashMap<>(metadata));
            return record;
        }
    }
}
