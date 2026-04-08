package ai.core.agent;

import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Usage;
import ai.core.persistence.PersistenceProvider;
import ai.core.session.BackgroundTaskMonitor;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import ai.core.tool.subagent.SubagentTaskRegistry;
import core.framework.util.Maps;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    private final String parentTaskId;
    private final Map<String, Object> customVariables;
    private final ToolCallAsyncTaskManager asyncTaskManager;
    private final AttachedContent attachedContent;
    private final PersistenceProvider persistenceProvider;
    private final SubagentTaskRegistry subagentTaskRegistry;
    private final SubagentOutputSinkFactory subagentOutputSinkFactory;
    private BackgroundTaskMonitor backgroundTaskMonitor;
    private LLMProvider llmProvider;
    private String model;
    private StreamingCallback streamingCallback;
    private List<AbstractLifecycle> lifecycles;
    private Consumer<Usage> tokenCostCallback;

    private ExecutionContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.parentTaskId = builder.parentTaskId;
        this.attachedContent = builder.attachedContent;
        this.customVariables = Maps.newHashMap();
        this.customVariables.putAll(builder.customVariables);
        this.asyncTaskManager = builder.asyncTaskManager;
        this.persistenceProvider = builder.persistenceProvider;
        this.subagentTaskRegistry = builder.subagentTaskRegistry;
        this.subagentOutputSinkFactory = builder.subagentOutputSinkFactory;
        this.backgroundTaskMonitor = builder.backgroundTaskMonitor;
    }

    public String getSessionId() {
        return sessionId;
    }


    public String getUserId() {
        return userId;
    }

    public String getParentTaskId() {
        return parentTaskId;
    }

    public Map<String, Object> getCustomVariables() {
        return customVariables;
    }

    public ToolCallAsyncTaskManager getAsyncTaskManager() {
        return asyncTaskManager;
    }

    public SubagentTaskRegistry getSubagentTaskRegistry() {
        return subagentTaskRegistry;
    }

    public SubagentOutputSinkFactory getSubagentOutputSinkFactory() {
        return subagentOutputSinkFactory;
    }

    public BackgroundTaskMonitor getBackgroundTaskMonitor() {
        return backgroundTaskMonitor;
    }

    public void setBackgroundTaskMonitor(BackgroundTaskMonitor backgroundTaskMonitor) {
        this.backgroundTaskMonitor = backgroundTaskMonitor;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public StreamingCallback getStreamingCallback() {
        return streamingCallback;
    }

    public void setStreamingCallback(StreamingCallback streamingCallback) {
        this.streamingCallback = streamingCallback;
    }

    public List<AbstractLifecycle> getLifecycle() {
        return lifecycles;
    }

    public void setLifecycles(List<AbstractLifecycle> lifecycles) {
        this.lifecycles = lifecycles;
    }

    public PersistenceProvider getPersistenceProvider() {
        return persistenceProvider;
    }

    public Consumer<Usage> getTokenCostCallback() {
        return tokenCostCallback;
    }

    public void setTokenCostCallback(Consumer<Usage> tokenCostCallback) {
        this.tokenCostCallback = tokenCostCallback;
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private String parentTaskId;
        private ToolCallAsyncTaskManager asyncTaskManager;
        private AttachedContent attachedContent;
        private PersistenceProvider persistenceProvider;
        private SubagentTaskRegistry subagentTaskRegistry;
        private SubagentOutputSinkFactory subagentOutputSinkFactory;
        private BackgroundTaskMonitor backgroundTaskMonitor;
        private final Map<String, Object> customVariables = Maps.newHashMap();

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder parentTaskId(String parentTaskId) {
            this.parentTaskId = parentTaskId;
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

        public Builder persistenceProvider(PersistenceProvider persistenceProvider) {
            this.persistenceProvider = persistenceProvider;
            return this;
        }

        public Builder subagentTaskRegistry(SubagentTaskRegistry subagentTaskRegistry) {
            this.subagentTaskRegistry = subagentTaskRegistry;
            return this;
        }

        public Builder subagentOutputSinkFactory(SubagentOutputSinkFactory subagentOutputSinkFactory) {
            this.subagentOutputSinkFactory = subagentOutputSinkFactory;
            return this;
        }

        public Builder backgroundTaskMonitor(BackgroundTaskMonitor backgroundTaskMonitor) {
            this.backgroundTaskMonitor = backgroundTaskMonitor;
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(this);
        }
    }

    public static class AttachedContent {
        public static AttachedContent ofUrl(String url, AttachedContentType type) {
            var content = new AttachedContent();
            content.url = url;
            content.type = type;
            return content;
        }

        public static AttachedContent ofBase64(String data, String mediaType, AttachedContentType type) {
            return ofBase64(data, mediaType, type, null);
        }

        public static AttachedContent ofBase64(String data, String mediaType, AttachedContentType type, String filename) {
            var content = new AttachedContent();
            content.data = data;
            content.mediaType = mediaType;
            content.type = type;
            content.filename = filename;
            return content;
        }

        public String url;
        public String data;
        public String mediaType;
        public String filename;
        public AttachedContentType type;

        public boolean isBase64() {
            return data != null;
        }

        public enum AttachedContentType {
            IMAGE,
            PDF
        }
    }
}
