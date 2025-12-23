package ai.core.memory.history;

import ai.core.llm.domain.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a chat session with its messages and metadata.
 *
 * @author xander
 */
public class ChatSession {

    public static Builder builder() {
        return new Builder();
    }

    private String id;
    private String userId;
    private String agentId;
    private String title;
    private List<Message> messages;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, Object> metadata;

    public ChatSession() {
        this.id = UUID.randomUUID().toString();
        this.messages = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // ==================== Getters ====================

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getTitle() {
        return title;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public int getMessageCount() {
        return messages != null ? messages.size() : 0;
    }

    // ==================== Setters ====================

    public void setId(String id) {
        this.id = id;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // ==================== Utility Methods ====================

    public void addMessage(Message message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        this.updatedAt = Instant.now();
    }

    public void addMessages(List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.addAll(newMessages);
        this.updatedAt = Instant.now();
    }

    /**
     * Generate a title from the first user message.
     */
    public String generateTitle() {
        if (messages == null || messages.isEmpty()) {
            return "New Chat";
        }
        for (Message msg : messages) {
            if (msg.role != null && "USER".equals(msg.role.name()) && msg.content != null) {
                String content = msg.content.trim();
                if (content.length() > 50) {
                    return content.substring(0, 47) + "...";
                }
                return content;
            }
        }
        return "New Chat";
    }

    @Override
    public String toString() {
        return "ChatSession{id='" + id + "', userId='" + userId + "', title='" + title
            + "', messageCount=" + getMessageCount() + ", createdAt=" + createdAt + '}';
    }

    // ==================== Builder ====================

    public static class Builder {
        private final ChatSession session = new ChatSession();

        public Builder id(String id) {
            session.id = id;
            return this;
        }

        public Builder userId(String userId) {
            session.userId = userId;
            return this;
        }

        public Builder agentId(String agentId) {
            session.agentId = agentId;
            return this;
        }

        public Builder title(String title) {
            session.title = title;
            return this;
        }

        public Builder messages(List<Message> messages) {
            session.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            session.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            session.updatedAt = updatedAt;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            session.metadata = metadata;
            return this;
        }

        public ChatSession build() {
            if (session.title == null || session.title.isBlank()) {
                session.title = session.generateTitle();
            }
            return session;
        }
    }
}
