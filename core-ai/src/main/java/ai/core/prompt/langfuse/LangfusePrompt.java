package ai.core.prompt.langfuse;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response model for Langfuse prompt API
 *
 * @author stephen
 */
public class LangfusePrompt {
    @JsonProperty("id")
    private String id;

    @JsonProperty("createdAt")
    private Instant createdAt;

    @JsonProperty("updatedAt")
    private Instant updatedAt;

    @JsonProperty("projectId")
    private String projectId;

    @JsonProperty("createdBy")
    private String createdBy;

    @JsonProperty("name")
    private String name;

    @JsonProperty("version")
    private Integer version;

    @JsonProperty("type")
    private String type;  // "text" or "chat"

    @JsonProperty("prompt")
    private String prompt;  // For text prompts, this is a string; for chat prompts, use chatPrompt

    @JsonProperty("config")
    private Map<String, Object> config;

    @JsonProperty("labels")
    private List<String> labels;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("commitMessage")
    private String commitMessage;

    @JsonProperty("isActive")
    private Boolean isActive;

    @JsonProperty("resolutionGraph")
    private Object resolutionGraph;

    // Chat-specific field (when type = "chat")
    private List<ChatMessage> chatPrompt;

    public LangfusePrompt() {
        this.config = new HashMap<>();
        this.labels = new ArrayList<>();
        this.tags = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public Boolean isActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Object getResolutionGraph() {
        return resolutionGraph;
    }

    public void setResolutionGraph(Object resolutionGraph) {
        this.resolutionGraph = resolutionGraph;
    }

    public List<ChatMessage> getChatPrompt() {
        return chatPrompt;
    }

    public void setChatPrompt(List<ChatMessage> chatPrompt) {
        this.chatPrompt = chatPrompt;
    }

    /**
     * Check if this is a text prompt
     */
    public boolean isTextPrompt() {
        return "text".equalsIgnoreCase(type);
    }

    /**
     * Check if this is a chat prompt
     */
    public boolean isChatPrompt() {
        return "chat".equalsIgnoreCase(type);
    }

    /**
     * Get the prompt content as string
     * For text prompts, returns the prompt string
     * For chat prompts, concatenates messages
     */
    public String getPromptContent() {
        if (isTextPrompt()) {
            return prompt;
        } else if (isChatPrompt() && chatPrompt != null) {
            var sb = new StringBuilder();
            for (int i = 0; i < chatPrompt.size(); i++) {
                ChatMessage msg = chatPrompt.get(i);
                sb.append(msg.getRole()).append(": ").append(msg.getContent());
                if (i < chatPrompt.size() - 1) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }
        return prompt;
    }

    /**
     * Chat message for chat-type prompts
     */
    public static class ChatMessage {
        @JsonProperty("role")
        private String role;  // "system", "user", "assistant"

        @JsonProperty("content")
        private String content;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
