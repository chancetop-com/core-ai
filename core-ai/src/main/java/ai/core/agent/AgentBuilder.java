package ai.core.agent;

import ai.core.llm.LLMProvider;
import ai.core.memory.memories.NaiveMemory;
import ai.core.prompt.SystemVariables;
import ai.core.rag.RagConfig;
import ai.core.reflection.ReflectionConfig;
import ai.core.termination.terminations.MaxRoundTermination;
import ai.core.termination.terminations.StopMessageTermination;
import ai.core.tool.ToolCall;
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
    private Boolean useGroupContext = false;
    private Boolean enableReflection = false;
    private Integer maxToolCallCount;

    public AgentBuilder promptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
        return this;
    }

    public AgentBuilder maxToolCallCount(Integer maxToolCallCount) {
        this.maxToolCallCount = maxToolCallCount;
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

    public AgentBuilder ragConfig(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
        return this;
    }

    public AgentBuilder reflectionConfig(ReflectionConfig config) {
        this.reflectionConfig = config;
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

    public Agent build() {
        var agent = new Agent();
        this.nodeType = NodeType.AGENT;
        // default name and description
        if (name == null) {
            name = "assistant-agent";
        }
        if (description == null) {
            description = "assistant agent that help with user";
        }
        build(agent);
        agent.systemPrompt = this.systemPrompt == null ? "" : this.systemPrompt;
        agent.promptTemplate = this.promptTemplate == null ? "" : this.promptTemplate;
        agent.maxToolCallCount = this.maxToolCallCount == null ? 3 : this.maxToolCallCount;
        agent.temperature = this.temperature;
        agent.model = this.model;
        agent.llmProvider = this.llmProvider;
        agent.toolCalls = this.toolCalls;
        agent.ragConfig = this.ragConfig;
        agent.reflectionConfig = this.reflectionConfig;
        agent.useGroupContext = this.useGroupContext;
        agent.setPersistence(new AgentPersistence());
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
        if (agent.longTernMemory == null) {
            agent.longTernMemory = new NaiveMemory();
        }

        var systemVariables = agent.getSystemVariables();
        systemVariables.put(SystemVariables.AGENT_TOOLS, toolCalls.stream().map(ToolCall::toString).collect(Collectors.joining(";")));
        return agent;
    }

    @Override
    protected AgentBuilder self() {
        return this;
    }
}
