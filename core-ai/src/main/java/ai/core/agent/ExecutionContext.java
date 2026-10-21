package ai.core.agent;

import ai.core.agent.budget.RunBudget;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.agent.subagent.SubagentThreadRegistry;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Usage;
import ai.core.media.MediaProvider;
import ai.core.persistence.PersistenceProvider;
import ai.core.sandbox.Sandbox;
import ai.core.session.BackgroundTaskManager;
import ai.core.tool.OutboundCallerContext.Caller;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.prompt.PromptInject;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import ai.core.tool.tools.TodoStoreFactory;
import core.framework.util.Maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public final class ExecutionContext {
    // custom variables with this prefix carry internal runtime objects, never serialized into tool argument payloads
    public static final String INTERNAL_VARIABLE_PREFIX = "__";
    public static ExecutionContextBuilder builder() {
        return new ExecutionContextBuilder();
    }

    public static ExecutionContext empty() {
        return new ExecutionContextBuilder().build();
    }

    private final String sessionId;
    private final String userId;
    private Caller caller;
    private final String taskId;
    private final String taskName;
    private final Map<String, Object> customVariables;
    private final ToolCallAsyncTaskManager asyncTaskManager;
    private List<AttachedContent> attachedContents;
    private final PersistenceProvider persistenceProvider;
    private final SubagentOutputSinkFactory subagentOutputSinkFactory;
    private final TodoStoreFactory todoStoreFactory;
    private final List<PromptInject> promptSections;
    private Sandbox sandbox;
    private BackgroundTaskManager backgroundTaskManager;
    private CancellationToken cancellationToken;
    private LLMProvider llmProvider;
    private MediaProvider mediaProvider;
    private MediaProvider imageMediaProvider;
    private MediaProvider videoMediaProvider;
    private String model;
    private String multiModalModel;
    private boolean visionNative = true;
    private StreamingCallback streamingCallback;
    private final ThreadLocal<String> currentToolCallId = new ThreadLocal<>();
    private List<AbstractLifecycle> lifecycles;
    private Consumer<Usage> tokenCostCallback;
    private Map<String, SubAgentConfig> subAgentConfigs;
    private ToolRegistry toolRegistry;
    private AgentProfileRegistry agentProfileRegistry;
    private RunBudget runBudget;
    private volatile SubagentThreadRegistry subagentThreads;

    ExecutionContext(ExecutionContextBuilder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.caller = builder.caller;
        this.taskId = builder.taskId;
        this.taskName = builder.taskName;
        this.attachedContents = builder.attachedContent == null ? null : List.of(builder.attachedContent);
        this.customVariables = Maps.newHashMap();
        this.customVariables.putAll(builder.customVariables);
        this.asyncTaskManager = builder.asyncTaskManager;
        this.persistenceProvider = builder.persistenceProvider;
        this.subagentOutputSinkFactory = builder.subagentOutputSinkFactory;
        this.todoStoreFactory = builder.todoStoreFactory;
        this.promptSections = new ArrayList<>(builder.promptSections);
        this.sandbox = builder.sandbox;
        this.backgroundTaskManager = builder.backgroundTaskManager;
        this.cancellationToken = builder.cancellationToken;
        this.runBudget = builder.runBudget;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    /** Caller identity (business-side external_id, manager id, metadata) resolved from the authenticated user. */
    public Caller getCaller() {
        return caller;
    }

    public void setCaller(Caller caller) {
        this.caller = caller;
    }

    public boolean isSubagent() {
        return sessionId != null && sessionId.startsWith("subagent:");
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public Map<String, Object> getCustomVariables() {
        return customVariables;
    }

    public ToolCallAsyncTaskManager getAsyncTaskManager() {
        return asyncTaskManager;
    }

    public SubagentOutputSinkFactory getSubagentOutputSinkFactory() {
        return subagentOutputSinkFactory;
    }

    public TodoStoreFactory getTodoStoreFactory() {
        return todoStoreFactory;
    }

    public List<PromptInject> getPromptSections() {
        return promptSections;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void sandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public BackgroundTaskManager getTaskManager() {
        return backgroundTaskManager;
    }

    public void setTaskManager(BackgroundTaskManager backgroundTaskManager) {
        this.backgroundTaskManager = backgroundTaskManager;
    }

    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    public void setCancellationToken(CancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    public void throwIfCancelled() {
        if (cancellationToken != null) cancellationToken.throwIfCancelled();
    }

    public boolean isCancelled() {
        return cancellationToken != null && cancellationToken.isCancelled();
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
        return attachedContents == null || attachedContents.isEmpty() ? null : attachedContents.getFirst();
    }

    public List<AttachedContent> getAttachedContents() {
        return attachedContents;
    }

    public void setAttachedContents(List<AttachedContent> attachedContents) {
        this.attachedContents = attachedContents == null || attachedContents.isEmpty() ? null : List.copyOf(attachedContents);
    }

    public void setLlmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public MediaProvider getMediaProvider() {
        return mediaProvider;
    }

    public void setMediaProvider(MediaProvider mediaProvider) {
        this.mediaProvider = mediaProvider;
    }

    public MediaProvider getImageMediaProvider() {
        return imageMediaProvider != null ? imageMediaProvider : mediaProvider;
    }

    public void setImageMediaProvider(MediaProvider imageMediaProvider) {
        this.imageMediaProvider = imageMediaProvider;
    }

    public MediaProvider getVideoMediaProvider() {
        return videoMediaProvider != null ? videoMediaProvider : mediaProvider;
    }

    public void setVideoMediaProvider(MediaProvider videoMediaProvider) {
        this.videoMediaProvider = videoMediaProvider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getMultiModalModel() {
        return multiModalModel;
    }

    public void setMultiModalModel(String multiModalModel) {
        this.multiModalModel = multiModalModel;
    }

    public boolean isVisionNative() {
        return visionNative;
    }

    public void setVisionNative(boolean visionNative) {
        this.visionNative = visionNative;
    }

    public StreamingCallback getStreamingCallback() {
        return streamingCallback;
    }

    public void setStreamingCallback(StreamingCallback streamingCallback) {
        this.streamingCallback = streamingCallback;
    }

    public void setCurrentToolCallId(String toolCallId) {
        if (toolCallId == null) {
            currentToolCallId.remove();
        } else {
            currentToolCallId.set(toolCallId);
        }
    }

    public void clearCurrentToolCallId() {
        currentToolCallId.remove();
    }

    public String getCurrentToolCallId() {
        return currentToolCallId.get();
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

    public Map<String, SubAgentConfig> getSubAgentConfigs() {
        return subAgentConfigs;
    }

    public void setSubAgentConfigs(Map<String, SubAgentConfig> subAgentConfigs) {
        this.subAgentConfigs = subAgentConfigs;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public AgentProfileRegistry getAgentProfileRegistry() {
        return agentProfileRegistry;
    }

    public void setAgentProfileRegistry(AgentProfileRegistry agentProfileRegistry) {
        this.agentProfileRegistry = agentProfileRegistry;
    }

    /**
     * The session's subagent threads, created on first use. Subagent contexts share the registry of
     * the context they were spawned from, so nested subagents register into the same session.
     */
    public SubagentThreadRegistry getSubagentThreads() {
        var registry = subagentThreads;
        if (registry == null) {
            synchronized (this) {
                registry = subagentThreads;
                if (registry == null) {
                    registry = new SubagentThreadRegistry(sessionId, persistenceProvider);
                    subagentThreads = registry;
                }
            }
        }
        return registry;
    }

    public void setSubagentThreads(SubagentThreadRegistry registry) {
        this.subagentThreads = registry;
    }

    /** True when this context already owns or shares a thread registry; lets a close skip untouched sessions. */
    public boolean hasSubagentThreads() {
        return subagentThreads != null;
    }

    public RunBudget getRunBudget() {
        return runBudget;
    }

    public void setRunBudget(RunBudget runBudget) {
        this.runBudget = runBudget;
    }
}
