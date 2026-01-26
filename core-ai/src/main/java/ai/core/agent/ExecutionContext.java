package ai.core.agent;

import ai.core.llm.LLMProvider;
import ai.core.tool.ToolCallAsyncTaskManager;
import core.framework.util.Maps;

import java.util.Map;

/**
 * @author stephen
 */
public final class ExecutionContext {
    public static Builder builder() {
        return new Builder();
    }

    public static ExecutionContext empty() {
        return new Builder().build();
    }

    private final String sessionId;
    private final String userId;
    private final Map<String, Object> customVariables;
    private final ToolCallAsyncTaskManager asyncTaskManager;
    private final AttachedContent attachedContent;
    private LLMProvider llmProvider;

    private ExecutionContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.attachedContent = builder.attachedContent;
        this.customVariables = Maps.newHashMap();
        this.customVariables.putAll(builder.customVariables);
        this.asyncTaskManager = builder.asyncTaskManager;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public Map<String, Object> getCustomVariables() {
        return customVariables;
    }

    public ToolCallAsyncTaskManager getAsyncTaskManager() {
        return asyncTaskManager;
    }

    public Object getCustomVariable(String key) {
        return customVariables.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCustomVariable(String key, Class<T> type) {
        return (T) customVariables.get(key);
    }

    public boolean hasCustomVariable(String key) {
        return customVariables.containsKey(key);
    }

    public LLMProvider getLlmProvider() {
        return llmProvider;
    }

    public AttachedContent getAttachedContent() {
        return attachedContent;
    }

    public void setLlmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private ToolCallAsyncTaskManager asyncTaskManager;
        private AttachedContent attachedContent;
        private final Map<String, Object> customVariables = Maps.newHashMap();

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder customVariables(Map<String, Object> customVariables) {
            if (customVariables != null) {
                this.customVariables.putAll(customVariables);
            }
            return this;
        }

        public Builder customVariable(String key, Object value) {
            this.customVariables.put(key, value);
            return this;
        }

        public Builder asyncTaskManager(ToolCallAsyncTaskManager asyncTaskManager) {
            this.asyncTaskManager = asyncTaskManager;
            return this;
        }

        public Builder attachedContent(AttachedContent attachedContent) {
            this.attachedContent = attachedContent;
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(this);
        }
    }

    public static class AttachedContent {
        public static AttachedContent of(String url, AttachedContentType type) {
            var content = new AttachedContent();
            content.url = url;
            content.type = type;
            return content;
        }

        public String url;
        public AttachedContentType type;

        public enum AttachedContentType {
            IMAGE,
            PDF
        }
    }
}
