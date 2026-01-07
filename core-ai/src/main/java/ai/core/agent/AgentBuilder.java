package ai.core.agent;

import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.LLMProvider;
import ai.core.compression.Compression;
import ai.core.compression.CompressionLifecycle;
import ai.core.memory.MemoryConfig;
import ai.core.memory.MemoryLifecycle;
import ai.core.memory.Memory;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.prompt.SystemVariables;
import ai.core.prompt.langfuse.LangfusePromptProvider;
import ai.core.prompt.langfuse.LangfusePromptProviderRegistry;
import ai.core.rag.RagConfig;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionListener;
import ai.core.termination.terminations.MaxRoundTermination;
import ai.core.termination.terminations.StopMessageTermination;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import core.framework.util.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class AgentBuilder extends NodeBuilder<AgentBuilder, Agent> {
    private String systemPrompt;
    private String promptTemplate;
    private LLMProvider llmProvider;
    private List<ToolCall> toolCalls = Lists.newArrayList();
    private RagConfig ragConfig;
    private Double temperature;
    private String model;
    private ReflectionConfig reflectionConfig;
    private ReflectionListener reflectionListener;
    private Boolean useGroupContext = false;
    private Boolean enableReflection = false;
    private Integer maxTurnNumber;
    private Compression compression;
    private boolean compressionEnabled = true;

    // Memory configuration
    private Memory memory;
    private MemoryConfig memoryConfig;

    // Langfuse prompt integration (simplified - just names needed)
    private String langfuseSystemPromptName;
    private String langfusePromptTemplateName;
    private Integer langfusePromptVersion;
    private String langfusePromptLabel;


    public AgentBuilder promptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
        return this;
    }

    public AgentBuilder maxTurn(Integer maxTurnNumber) {
        this.maxTurnNumber = maxTurnNumber;
        return this;
    }

    public AgentBuilder compression(Compression compression) {
        this.compression = compression;
        return this;
    }

    public AgentBuilder enableCompression(boolean enabled) {
        this.compressionEnabled = enabled;
        return this;
    }

    public AgentBuilder unifiedMemory(Memory memory) {
        this.memory = memory;
        return this;
    }

    public AgentBuilder unifiedMemory(Memory memory, MemoryConfig config) {
        this.memory = memory;
        this.memoryConfig = config;
        return this;
    }

    public AgentBuilder systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public AgentBuilder temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public AgentBuilder model(String model) {
        this.model = model;
        return this;
    }

    public AgentBuilder llmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }

    public AgentBuilder toolCalls(List<? extends ToolCall> toolCalls) {
        this.toolCalls = new ArrayList<>(toolCalls);
        return this;
    }

    public AgentBuilder mcpServers(List<String> serverNames, List<String> includes) {
        return mcpServers(serverNames, includes, List.of());
    }

    public AgentBuilder mcpServers(List<String> serverNames, List<String> includes, List<String> excludes) {
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) {
            throw new IllegalStateException("MCP servers requested but McpClientManager is not configured. "
                    + "Please configure mcp.servers in your properties file.");
        }
        this.toolCalls.addAll(McpToolCalls.from(manager, serverNames, includes, excludes));
        return this;
    }

    public AgentBuilder ragConfig(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
        return this;
    }

    public AgentBuilder reflectionConfig(ReflectionConfig config) {
        this.reflectionConfig = config;
        return this;
    }

    public AgentBuilder reflectionListener(ReflectionListener listener) {
        this.reflectionListener = listener;
        return this;
    }

    public AgentBuilder useGroupContext(Boolean useGroupContext) {
        this.useGroupContext = useGroupContext;
        return this;
    }

    public AgentBuilder enableReflection(Boolean enableReflection) {
        this.enableReflection = enableReflection;
        return this;
    }

    public AgentBuilder reflectionEvaluationCriteria(String evaluationCriteria) {
        this.reflectionConfig = ReflectionConfig.withEvaluationCriteria(evaluationCriteria);
        return this;
    }

    public AgentBuilder langfuseSystemPrompt(String promptName) {
        this.langfuseSystemPromptName = promptName;
        return this;
    }

    public AgentBuilder langfusePromptTemplate(String promptName) {
        this.langfusePromptTemplateName = promptName;
        return this;
    }

    public AgentBuilder langfusePromptVersion(Integer version) {
        this.langfusePromptVersion = version;
        return this;
    }

    public AgentBuilder langfusePromptLabel(String label) {
        this.langfusePromptLabel = label;
        return this;
    }

    public AgentBuilder agentLifecycle(List<AbstractLifecycle> agentLifecycles) {
        this.agentLifecycles = agentLifecycles;
        return this;
    }

    public Agent build() {
        beforeAgentBuildLifecycle();
        var agent = new Agent();
        this.nodeType = NodeType.AGENT;
        // default name and description
        if (name == null) {
            name = "assistant";
        }
        if (description == null) {
            description = "assistant agent that help with user";
        }
        build(agent);

        // Fetch prompts from Langfuse if configured
        fetchLangfusePromptsIfConfigured();
        copyValue(agent);
        var systemVariables = agent.getSystemVariables();
        systemVariables.put(SystemVariables.AGENT_TOOLS, toolCalls.stream().map(ToolCall::toString).collect(Collectors.joining(";")));
        afterAgentBuildLifecycle(agent);
        return agent;
    }

    private void beforeAgentBuildLifecycle() {
        agentLifecycles.forEach(alc -> alc.beforeAgentBuild(this));
    }

    private void afterAgentBuildLifecycle(Agent agent) {
        agentLifecycles.forEach(alc -> alc.afterAgentBuild(agent));
    }

    private void copyValue(Agent agent) {
        agent.systemPrompt = this.systemPrompt == null ? "you are a helpful assistant" : this.systemPrompt;
        agent.promptTemplate = this.promptTemplate == null ? "" : this.promptTemplate;
        agent.maxTurnNumber = this.maxTurnNumber == null ? 20 : this.maxTurnNumber;
        agent.temperature = this.temperature;
        agent.model = this.model;
        agent.llmProvider = this.llmProvider;
        if (agent.llmProvider == null) {
            throw new Error("llmProvider is required for agent, please set it with llmProvider() method");
        }
        agent.toolCalls = this.toolCalls;
        agent.ragConfig = this.ragConfig;
        agent.reflectionConfig = this.reflectionConfig;
        agent.reflectionListener = this.reflectionListener;
        agent.useGroupContext = this.useGroupContext;
        agent.setPersistence(new AgentPersistence());
        agent.agentLifecycles = new ArrayList<>(agentLifecycles);
        if (this.enableReflection && this.reflectionConfig == null) {
            agent.reflectionConfig = ReflectionConfig.defaultReflectionConfig();
        }
        if (agent.reflectionConfig != null) {
            agent.setMaxRound(agent.reflectionConfig.maxRound());
            agent.addTermination(new MaxRoundTermination());
            agent.addTermination(new StopMessageTermination());
        }
        if (agent.ragConfig == null) {
            agent.ragConfig = new RagConfig();
        }

        if (this.compressionEnabled) {
            agent.compression = this.compression != null
                    ? this.compression
                    : new Compression(this.llmProvider, this.model);
            agent.agentLifecycles.add(new CompressionLifecycle(agent.compression));
        }
        configureMemory(agent);
    }

    private void configureMemory(Agent agent) {
        if (this.memory == null) {
            return;
        }
        var config = this.memoryConfig != null
            ? this.memoryConfig
            : MemoryConfig.defaultConfig();

        var lifecycle = new MemoryLifecycle(this.memory, config.getMaxRecallRecords());
        agent.agentLifecycles.add(lifecycle);

        // Only auto-register MemoryRecallTool if autoRecall is enabled
        if (config.isAutoRecall()) {
            var memoryRecallTool = lifecycle.getMemoryRecallTool();
            agent.toolCalls.add(memoryRecallTool);
        }
    }

    private void fetchLangfusePromptsIfConfigured() {
        // Check if any Langfuse prompts are requested
        if (langfuseSystemPromptName == null && langfusePromptTemplateName == null) {
            return;
        }

        // Get provider from registry
        var provider = LangfusePromptProviderRegistry.getProvider();
        if (provider == null) {
            throw new IllegalStateException("Langfuse prompts are configured but Langfuse provider is not initialized. " + "Please configure langfuse.prompt.base.url in your properties file.");
        }

        try {
            if (langfuseSystemPromptName != null) {
                var prompt = langfusePromptVersion != null ? provider.getPrompt(langfuseSystemPromptName, langfusePromptVersion) : langfusePromptLabel != null ? provider.getPromptByLabel(langfuseSystemPromptName, langfusePromptLabel) : provider.getPrompt(langfuseSystemPromptName);
                this.systemPrompt = prompt.getPromptContent();
            }
            if (langfusePromptTemplateName != null) {
                var prompt = langfusePromptVersion != null ? provider.getPrompt(langfusePromptTemplateName, langfusePromptVersion) : langfusePromptLabel != null ? provider.getPromptByLabel(langfusePromptTemplateName, langfusePromptLabel) : provider.getPrompt(langfusePromptTemplateName);
                this.promptTemplate = prompt.getPromptContent();
            }
        } catch (LangfusePromptProvider.LangfusePromptException e) {
            throw new RuntimeException("Failed to fetch prompts from Langfuse", e);
        }
    }

    @Override
    protected AgentBuilder self() {
        return this;
    }
}
