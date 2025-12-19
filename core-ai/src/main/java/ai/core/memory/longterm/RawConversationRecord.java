package ai.core.memory.longterm;

import ai.core.llm.domain.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Raw conversation record for source tracking.
 * Optional storage, configurable by user.
 *
 * @author xander
 */
public class RawConversationRecord {
    public static Builder builder() {
        return new Builder();
    }

    private String id;
    private String sessionId;
    private String userId;
    private List<Message> messages;
    private Instant createdAt;
    private Instant expiresAt;

    public RawConversationRecord() {
        this.id = UUID.randomUUID().toString();
        this.messages = new ArrayList<>();
        this.createdAt = Instant.now();
    }

    /**
     * Get messages within the specified turn range.
     *
     * @param startTurn start turn index (inclusive)
     * @param endTurn   end turn index (inclusive)
     * @return messages in the range, or empty list if out of bounds
     */
    public List<Message> getMessagesInRange(int startTurn, int endTurn) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, startTurn);
        int end = Math.min(messages.size() - 1, endTurn);
        if (start > end || start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, end + 1);
    }

    /**
     * Check if this record has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public void addMessage(Message message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public static class Builder {
        private String id;
        private String sessionId;
        private String userId;
        private List<Message> messages;
        private Instant expiresAt;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder retentionDays(int days) {
            this.expiresAt = Instant.now().plus(Duration.ofDays(days));
            return this;
        }

        public RawConversationRecord build() {
            RawConversationRecord record = new RawConversationRecord();
            if (id != null) record.setId(id);
            if (sessionId != null) record.setSessionId(sessionId);
            if (userId != null) record.setUserId(userId);
            if (messages != null) record.setMessages(messages);
            if (expiresAt != null) record.setExpiresAt(expiresAt);
            return record;
        }
    }
}
