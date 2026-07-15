package ai.core.agent;

import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.agent.lifecycle.ResponseValidationLifecycle;
import ai.core.context.Compression;
import ai.core.context.CompressionLifecycle;
import ai.core.context.ToolCallPruning;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.memory.Memory;
import ai.core.memory.MemoryConfig;

import ai.core.prompt.PromptInject;
import ai.core.rag.RagConfig;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionListener;
import ai.core.skill.SkillRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
import ai.core.tool.tools.SkillTool;
import ai.core.tool.tools.SubAgentToolCall;
import core.framework.util.Lists;
import core.framework.util.Maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public class AgentBuilder extends NodeBuilder<AgentBuilder, Agent> {
    String systemPrompt;
    final List<PromptInject> systemPromptSections = new ArrayList<>();
    String promptTemplate;
    LLMProvider llmProvider;
    final List<ToolCall> toolCalls = Lists.newArrayList();
    private List<String> toolNames;
    ToolRegistry toolRegistry;
    RagConfig ragConfig;
    Double temperature;
    String model;
    String multiModalModel;
    ReflectionConfig reflectionConfig;
    ReflectionListener reflectionListener;
    Boolean useGroupContext = Boolean.FALSE;
    Boolean enableReflection = Boolean.FALSE;
    Integer maxTurnNumber;
    Compression compression;
    private boolean compressionEnabled = true;
    boolean toolCallPruningEnabled = false;
    ToolCallPruning.Config toolCallPruningConfig;
    ReasoningEffort reasoningEffort;
    boolean doomLoopEnabled = true;
    int doomLoopWindowSize = 4;
    int doomLoopThreshold = 3;
    Memory memory;
    MemoryConfig memoryConfig;
    private SkillRegistry skillRegistry;
    List<SubAgentToolCall> subAgents = Lists.newArrayList();
    String langfuseSystemPromptName;
    String langfusePromptTemplateName;
    Integer langfusePromptVersion;
    String langfusePromptLabel;
    final Map<String, Object> extraSystemVariables = Maps.newHashMap();

    public AgentBuilder promptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
        return this;
    }

    public AgentBuilder maxTurn(Integer maxTurnNumber) {
        this.maxTurnNumber = maxTurnNumber;
        return this;
    }

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns, LLMProvider llmProvider, String summaryModel) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, 10000, llmProvider, resolveCompressionModel(), summaryModel);
        return this;
    }

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns, int keepMinTokens, LLMProvider llmProvider, String summaryModel) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, keepMinTokens, llmProvider, resolveCompressionModel(), summaryModel);
        return this;
    }

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, 10000, this.llmProvider, resolveCompressionModel(), resolveCompressionModel());
        return this;
    }

    public AgentBuilder compression(double triggerThreshold, int keepRecentTurns, int keepMinTokens) {
        this.compression = new Compression(triggerThreshold, keepRecentTurns, keepMinTokens, this.llmProvider, resolveCompressionModel(), resolveCompressionModel());
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

    public AgentBuilder systemPromptSection(PromptInject section) {
        this.systemPromptSections.add(section);
        return this;
    }

    public AgentBuilder systemPromptSections(List<PromptInject> sections) {
        this.systemPromptSections.clear();
        this.systemPromptSections.addAll(sections);
        return this;
    }

    public AgentBuilder extraSystemVariable(String key, Object value) {
        this.extraSystemVariables.put(key, value);
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

    public AgentBuilder multiModalModel(String multiModalModel) {
        this.multiModalModel = multiModalModel;
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

    public AgentBuilder toolNames(List<String> toolNames) {
        if (!toolCalls.isEmpty()) {
            throw new IllegalStateException("Cannot specify tool names when tool calls are already specified.");
        }
        this.toolNames = toolNames;
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
        tools.forEach(t -> t.setDiscoverable(Boolean.TRUE));
        this.toolCalls.addAll(tools);
        return this;
    }

    public AgentBuilder skillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
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

    public AgentBuilder toolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        return this;
    }


    public Agent build() {
        beforeAgentBuildLifecycle();
        var agent = new Agent();
        this.nodeType = NodeType.AGENT;
        if (name == null) {
            name = "assistant";
        }
        if (description == null) {
            description = "assistant agent that help with user";
        }
        build(agent);
        configureSubAgents();
        AgentAssembler.configureToolCallPruning(this);
        AgentAssembler.configureMemory(this);
        AgentAssembler.configureDoomLoop(this);
        configureResponseValidation();
        configureCompression();

        // Fetch prompts from Langfuse if configured
        AgentAssembler.fetchLangfusePromptsIfConfigured(this);
        configureSkills();
        AgentAssembler.configureToolDiscovery(this);
        configureSystemPrompt();
        configureToolRegistry();

        AgentAssembler.assemble(this, agent);

        afterAgentBuildLifecycle(agent);
        return agent;
    }

    private void configureToolRegistry() {
        if (toolNames != null && !toolNames.isEmpty()) {
            if (toolRegistry == null) {
                throw new IllegalStateException("toolNames is set but no ToolRegistry provided — pass a ToolRegistry via toolRegistry()");
            }
            toolRegistry = ToolRegistryFactory.derive(toolRegistry, Set.copyOf(toolNames));
            return;
        }
        if (toolRegistry == null) {
            toolRegistry = ToolRegistryFactory.createEmpty();
        }
        for (var tc : toolCalls) {
            toolRegistry.registerProvider(ListToolProvider.of(tc.getName(), List.of(tc)));
        }
    }

    private void beforeAgentBuildLifecycle() {
        agentLifecycles.forEach(alc -> alc.beforeAgentBuild(this));
    }

    private void afterAgentBuildLifecycle(Agent agent) {
        agentLifecycles.forEach(alc -> alc.afterAgentBuild(agent));
    }

    private void configureSubAgents() {
        if (this.subAgents != null && !this.subAgents.isEmpty()) {
            toolCalls.addAll(this.subAgents);
        }
    }

    private void configureSystemPrompt() {
        var sb = new StringBuilder(this.systemPrompt != null ? this.systemPrompt : "");
        for (var section : systemPromptSections) {
            var text = section.inject();
            if (!text.isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(text);
            }
        }
        this.systemPrompt = !sb.isEmpty() ? sb.toString() : "you are a helpful assistant";
    }

    private void configureResponseValidation() {
        agentLifecycles.add(new ResponseValidationLifecycle());
    }

    private void configureCompression() {
        if (compressionEnabled) {
            if (compression == null) {
                compression = new Compression(this.llmProvider, resolveCompressionModel());
            }
            agentLifecycles.add(new CompressionLifecycle(compression));
        }
    }

    private String resolveCompressionModel() {
        if (this.model != null) return this.model;
        return this.llmProvider != null ? this.llmProvider.config.getModel() : null;
    }

    private void configureSkills() {
        if (skillRegistry == null) return;
        if (skillRegistry.listAll().isEmpty()) return;
        if (toolCalls.stream().noneMatch(t -> SkillTool.TOOL_NAME.equals(t.getName()))) {
            toolCalls.add(SkillTool.builder().registry(skillRegistry).build());
        }
    }

    @Override
    protected AgentBuilder self() {
        return this;
    }
}
