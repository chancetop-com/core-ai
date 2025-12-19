package ai.core.memory.longterm;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    private String userId;
    private String content;
    private MemoryType type;

    // Weight and decay
    private double importance;
    private int accessCount;
    private double decayFactor;

    // Timestamps
    private Instant createdAt;
    private Instant lastAccessedAt;

    // Source tracking (for linking to raw conversation)
    private String sessionId;
    private String rawRecordId;
    private Integer startTurnIndex;
    private Integer endTurnIndex;

    // Extension
    private Map<String, Object> metadata;

    public MemoryRecord() {
        this.id = UUID.randomUUID().toString();
        this.importance = DEFAULT_IMPORTANCE;
        this.accessCount = 0;
        this.decayFactor = DEFAULT_DECAY_FACTOR;
        this.createdAt = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.metadata = new HashMap<>();
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public MemoryType getType() {
        return type;
    }

    public void setType(MemoryType type) {
        this.type = type;
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = importance;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }

    public void incrementAccessCount() {
        this.accessCount++;
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRawRecordId() {
        return rawRecordId;
    }

    public void setRawRecordId(String rawRecordId) {
        this.rawRecordId = rawRecordId;
    }

    public Integer getStartTurnIndex() {
        return startTurnIndex;
    }

    public void setStartTurnIndex(Integer startTurnIndex) {
        this.startTurnIndex = startTurnIndex;
    }

    public Integer getEndTurnIndex() {
        return endTurnIndex;
    }

    public void setEndTurnIndex(Integer endTurnIndex) {
        this.endTurnIndex = endTurnIndex;
    }

    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    /**
     * Calculate the effective score for ranking.
     * score = similarity × importance × decayFactor × frequencyBonus
     */
    public double calculateEffectiveScore(double similarity) {
        double frequencyBonus = 1.0 + FREQUENCY_BONUS_FACTOR * Math.log1p(accessCount);
        return similarity * importance * decayFactor * frequencyBonus;
    }

    public static class Builder {
        private String id;
        private String userId;
        private String content;
        private MemoryType type;
        private Double importance;
        private String sessionId;
        private String rawRecordId;
        private Integer startTurnIndex;
        private Integer endTurnIndex;
        private final Map<String, Object> metadata = new HashMap<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder type(MemoryType type) {
            this.type = type;
            return this;
        }

        public Builder importance(double importance) {
            this.importance = importance;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder rawRecordId(String rawRecordId) {
            this.rawRecordId = rawRecordId;
            return this;
        }

        public Builder turnRange(int start, int end) {
            this.startTurnIndex = start;
            this.endTurnIndex = end;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public MemoryRecord build() {
            MemoryRecord record = new MemoryRecord();
            if (id != null) record.setId(id);
            if (userId != null) record.setUserId(userId);
            if (content != null) record.setContent(content);
            if (type != null) {
                record.setType(type);
                record.setImportance(importance != null ? importance : type.getDefaultImportance());
            } else if (importance != null) {
                record.setImportance(importance);
            }
            if (sessionId != null) record.setSessionId(sessionId);
            if (rawRecordId != null) record.setRawRecordId(rawRecordId);
            if (startTurnIndex != null) record.setStartTurnIndex(startTurnIndex);
            if (endTurnIndex != null) record.setEndTurnIndex(endTurnIndex);
            if (!metadata.isEmpty()) record.setMetadata(new HashMap<>(metadata));
            return record;
        }
    }
}
