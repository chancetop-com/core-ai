package ai.core.agent;

import ai.core.agent.doomloop.DoomLoopLifecycle;
import ai.core.agent.doomloop.DoomLoopStrategy;
import ai.core.agent.doomloop.RepetitiveCallStrategy;
import ai.core.agent.doomloop.TodoReminderStrategy;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.context.Compression;
import ai.core.context.CompressionLifecycle;
import ai.core.context.ToolCallPruning;
import ai.core.context.ToolCallPruningLifecycle;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.memory.Memory;
import ai.core.memory.MemoryConfig;
import ai.core.memory.MemoryLifecycle;
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
import ai.core.tool.tools.SubAgentToolCall;
import ai.core.tool.tools.ToolActivationTool;
import ai.core.tool.tools.WriteTodosTool;
import core.framework.util.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class AgentBuilder extends NodeBuilder<AgentBuilder, Agent> {
    private String systemPrompt;
    private String promptTemplate;
    private LLMProvider llmProvider;
    private final List<ToolCall> toolCalls = Lists.newArrayList();
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
    private boolean toolCallPruningEnabled = false;
    private ToolCallPruning.Config toolCallPruningConfig;
    private ReasoningEffort reasoningEffort;
    private boolean doomLoopEnabled = true;
    private int doomLoopWindowSize = 4;
    private int doomLoopThreshold = 3;

    // Memory configuration
    private Memory memory;
    private MemoryConfig memoryConfig;

    // SubAgent configuration
    private List<SubAgentToolCall> subAgents = Lists.newArrayList();

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

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns, LLMProvider llmProvider, String summaryModel) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, 10000, llmProvider, this.model, summaryModel);
        return this;
    }

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns, int keepMinTokens, LLMProvider llmProvider, String summaryModel) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, keepMinTokens, llmProvider, this.model, summaryModel);
        return this;
    }

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, 10000, this.llmProvider, this.model, this.model);
        return this;
    }

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns, int keepMinTokens) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, keepMinTokens, this.llmProvider, this.model, this.model);
        return this;
    }

    public AgentBuilder compression(boolean enabled) {
        this.compressionEnabled = enabled;
        return this;
    }

    public AgentBuilder toolCallPruning(boolean enabled) {
        this.toolCallPruningEnabled = enabled;
        return this;
    }

    public AgentBuilder toolCallPruning(int keepRecentSegments, Set<String> excludeToolNames) {
        this.toolCallPruningConfig = new ToolCallPruning.Config(keepRecentSegments, excludeToolNames);
        this.toolCallPruningEnabled = true;
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
        this.toolCalls.addAll(0, toolCalls);
        return this;
    }

    public AgentBuilder mcpServers(List<String> serverNames) {
        return mcpServers(serverNames, null, null);
    }

    public AgentBuilder mcpServers(List<String> serverNames, List<String> includes) {
        return mcpServers(serverNames, includes, null);
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

    public AgentBuilder mcpServersDiscoverable(List<String> serverNames) {
        return mcpServersDiscoverable(serverNames, null, null);
    }

    public AgentBuilder mcpServersDiscoverable(List<String> serverNames, List<String> includes) {
        return mcpServersDiscoverable(serverNames, includes, null);
    }

    public AgentBuilder mcpServersDiscoverable(List<String> serverNames, List<String> includes, List<String> excludes) {
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) {
            throw new IllegalStateException("MCP servers requested but McpClientManager is not configured. "
                    + "Please configure mcp.servers in your properties file.");
        }
        var tools = McpToolCalls.from(manager, serverNames, includes, excludes);
        tools.forEach(t -> t.setDiscoverable(true));
        this.toolCalls.addAll(tools);
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
        this.agentLifecycles.addAll(agentLifecycles);
        return this;
    }

    public AgentBuilder addAgentLifecycle(AbstractLifecycle lifecycle) {
        this.agentLifecycles.add(lifecycle);
        return this;
    }

    public AgentBuilder subAgents(List<SubAgentToolCall> subAgents) {
        this.subAgents = new ArrayList<>(subAgents);
        return this;
    }

    public AgentBuilder reasoningEffort(ReasoningEffort reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
        return this;
    }

    public AgentBuilder doomLoopDetection(boolean enabled) {
        this.doomLoopEnabled = enabled;
        return this;
    }

    public AgentBuilder doomLoopWindowSize(int size) {
        this.doomLoopWindowSize = size;
        return this;
    }

    public AgentBuilder doomLoopThreshold(int threshold) {
        this.doomLoopThreshold = threshold;
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
        configureSubAgents(agent);
        configureToolCallPruning();
        configureMemory();
        configureDoomLoop();
        configureCompression();

        // Fetch prompts from Langfuse if configured
        fetchLangfusePromptsIfConfigured();
        configureToolDiscovery();
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

    private void configureSubAgents(Agent agent) {
        // Process subAgents: convert to SubAgentToolCall and add to toolCalls
        if (this.subAgents != null && !this.subAgents.isEmpty()) {
            for (var subAgent : this.subAgents) {
                subAgent.getSubAgent().setParentNode(agent);
                toolCalls.add(subAgent);
            }
        }

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
        agent.subAgents = this.subAgents;

        agent.ragConfig = this.ragConfig;
        agent.reflectionConfig = this.reflectionConfig;
        agent.reflectionListener = this.reflectionListener;
        agent.useGroupContext = this.useGroupContext;
        agent.setPersistence(new AgentPersistence());
        agent.agentLifecycles = new ArrayList<>(agentLifecycles);
        agent.reasoningEffort = this.reasoningEffort;
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
    }

    private void configureToolCallPruning() {
        if (this.toolCallPruningEnabled) {
            var pruningCfg = this.toolCallPruningConfig != null ? this.toolCallPruningConfig : ToolCallPruning.Config.defaultConfig();
            agentLifecycles.addFirst(new ToolCallPruningLifecycle(new ToolCallPruning(pruningCfg.keepRecentSegments(), pruningCfg.excludeToolNames())));
        }
    }

    private void configureDoomLoop() {
        var strategies = new ArrayList<DoomLoopStrategy>();
        if (doomLoopEnabled) {
            strategies.add(new RepetitiveCallStrategy(doomLoopWindowSize, doomLoopThreshold));
        }
        boolean hasWriteTodos = toolCalls.stream()
                .anyMatch(t -> WriteTodosTool.WT_TOOL_NAME.equals(t.getName()));
        if (hasWriteTodos) {
            strategies.add(new TodoReminderStrategy());
        }
        if (!strategies.isEmpty()) {
            agentLifecycles.add(new DoomLoopLifecycle(strategies));
        }
    }

    private void configureCompression() {
        if (compressionEnabled) {
            if (compression == null) {
                compression = new Compression(this.llmProvider, this.model);
            }
            agentLifecycles.add(new CompressionLifecycle(compression));
        }
    }


    private void configureToolDiscovery() {
        var discoverableTools = toolCalls.stream().filter(ToolCall::isDiscoverable).toList();
        if (!discoverableTools.isEmpty()) {
            for (var tool : discoverableTools) {
                tool.setLlmVisible(false);
            }
            toolCalls.add(ToolActivationTool.builder().allToolCalls(toolCalls).build());
        }
    }

    private void configureMemory() {
        if (this.memory == null) {
            return;
        }
        var config = this.memoryConfig != null
                ? this.memoryConfig
                : MemoryConfig.defaultConfig();

        var lifecycle = new MemoryLifecycle(this.memory, config.getMaxRecallRecords());
        agentLifecycles.add(lifecycle);

        // Only auto-register MemoryRecallTool if autoRecall is enabled
        if (config.isAutoRecall()) {
            var memoryRecallTool = lifecycle.getMemoryRecallTool();
            toolCalls.add(memoryRecallTool);
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
