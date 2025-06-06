package ai.core.flow.nodes;

import ai.core.agent.Agent;
import ai.core.agent.formatter.Formatters;
import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.flow.FlowNodeType;
import ai.core.llm.LLMProvider;
import ai.core.rag.RagConfig;
import ai.core.reflection.Reflection;
import ai.core.reflection.ReflectionConfig;
import ai.core.tool.ToolCall;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentFlowNode extends FlowNode<AgentFlowNode> {
    private String systemPrompt;
    private String description;
    private String promptTemplate;
    private Boolean useGroupContext;
    private Boolean reflectionEnabled;
    private Integer reflectionMaxRound;
    private Integer reflectionMinRound;
    private String reflectionPrompt;
    private String formatter;

    private LLMProvider llmProvider;
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private RagConfig ragConfig;

    private Agent agent;

    public AgentFlowNode() {
        super("Agent", "AI Agent Node", FlowNodeType.AGENT, AgentFlowNode.class);
    }

    public AgentFlowNode(String id, String name) {
        super(id, name, "Agent", "AI Agent Node", FlowNodeType.AGENT, AgentFlowNode.class);
    }

    public AgentFlowNode(String id, String name, String description, String systemPrompt, String promptTemplate) {
        super(id, name, "Agent", "AI Agent Node", FlowNodeType.AGENT, AgentFlowNode.class);
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        if (agent == null) throw new IllegalArgumentException("Agent is not setup successfully");
        return new FlowNodeResult(agent.run(input, null));
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        settings.forEach(setting -> {
            if (!(setting instanceof LLMFlowNode<?> || setting instanceof RagFlowNode<?> || setting instanceof ToolFlowNode<?>)) {
                throw new IllegalArgumentException("AgentFlowNode only support LLMFlowNode or RagFlowNode or ToolFlowNode settings: " + this.getName());
            }
            if (setting instanceof LLMFlowNode<?> llmFlowNode) {
                this.llmProvider = llmFlowNode.getLlmProvider();
            }
            if (setting instanceof RagFlowNode<?> ragFlowNode) {
                this.ragConfig = ragFlowNode.getRagConfig();
            }
            if (setting instanceof ToolFlowNode<?> toolFlowNode) {
                this.toolCalls.add(toolFlowNode.getToolCall());
            }
        });
        if (llmProvider == null) {
            throw new IllegalArgumentException("AgentFlowNode must have LLMFlowNode as setting: " + this.getName());
        }
        initNullParams();
        var builder = Agent.builder()
                .name(getName())
                .description(description)
                .systemPrompt(systemPrompt)
                .promptTemplate(promptTemplate)
                .llmProvider(llmProvider)
                .useGroupContext(useGroupContext)
                .reflectionConfig(new ReflectionConfig(reflectionEnabled, reflectionMaxRound, reflectionMinRound, reflectionPrompt))
                .ragConfig(ragConfig)
                .toolCalls(toolCalls);
        if (formatter != null) {
            builder.formatter(Formatters.getFormatter(formatter));
        }
        agent = builder.build();
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    public void setUseGroupContext(Boolean useGroupContext) {
        this.useGroupContext = useGroupContext;
    }

    public void setReflectionEnabled(Boolean reflectionEnabled) {
        this.reflectionEnabled = reflectionEnabled;
    }

    public void setReflectionMaxRound(Integer reflectionMaxRound) {
        this.reflectionMaxRound = reflectionMaxRound;
    }

    public void setReflectionMinRound(Integer reflectionMinRound) {
        this.reflectionMinRound = reflectionMinRound;
    }

    public void setReflectionPrompt(String reflectionPrompt) {
        this.reflectionPrompt = reflectionPrompt;
    }

    public void setFormatter(String formatter) {
        this.formatter = formatter;
    }

    public String getDescription() {
        return description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    public Boolean isUseGroupContext() {
        return useGroupContext;
    }

    public Boolean isReflectionEnabled() {
        return reflectionEnabled;
    }

    public Integer getReflectionMaxRound() {
        return reflectionMaxRound;
    }

    public Integer getReflectionMinRound() {
        return reflectionMinRound;
    }

    public String getReflectionPrompt() {
        return reflectionPrompt;
    }

    public String getFormatter() {
        return formatter;
    }

    public Agent getAgent() {
        return agent;
    }

    private void initNullParams() {
        if (description == null) {
            description = "AI Agent Assistant";
        }
        if (systemPrompt == null) {
            systemPrompt = "You are an AI assistant that helps users to do something.";
        }
        if (promptTemplate == null) {
            promptTemplate = "query:\n";
        }
        if (useGroupContext == null) {
            useGroupContext = false;
        }
        if (reflectionEnabled == null) {
            reflectionEnabled = false;
        }
        if (reflectionMaxRound == null) {
            reflectionMaxRound = 3;
        }
        if (reflectionMinRound == null) {
            reflectionMinRound = 1;
        }
        if (reflectionPrompt == null) {
            reflectionPrompt = Reflection.DEFAULT_REFLECTION_CONTINUE_TEMPLATE;
        }
    }

    @Override
    public void check(List<FlowNode<?>> settings) {
        var llmExist = settings.stream().anyMatch(setting -> setting instanceof LLMFlowNode<?>);
        if (!llmExist) {
            throw new IllegalArgumentException("AgentFlowNode must have LLMFlowNode as setting: " + this.getName());
        }
    }

    @Override
    public String serialization(AgentFlowNode node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(AgentFlowNode node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends FlowNode.Domain<Domain> {
        @Property(name = "system_prompt")
        public String systemPrompt;
        @Property(name = "description")
        public String description;
        @Property(name = "prompt_template")
        public String promptTemplate;
        @Property(name = "use_group_context")
        public Boolean useGroupContext;
        @Property(name = "reflection_enabled")
        public Boolean reflectionEnabled;
        @Property(name = "reflection_max_round")
        public Integer reflectionMaxRound;
        @Property(name = "reflection_min_round")
        public Integer reflectionMinRound;
        @Property(name = "reflection_prompt")
        public String reflectionPrompt;
        @Property(name = "formatter")
        public String formatter;

        @Override
        public Domain from(FlowNode<?> node) {
            this.fromBase(node);
            var agentNode = (AgentFlowNode) node;
            this.systemPrompt = agentNode.getSystemPrompt();
            this.description = agentNode.getDescription();
            this.promptTemplate = agentNode.getPromptTemplate();
            this.useGroupContext = agentNode.isUseGroupContext();
            this.formatter = agentNode.getFormatter();
            this.reflectionEnabled = agentNode.isReflectionEnabled();
            this.reflectionMaxRound = agentNode.getReflectionMaxRound();
            this.reflectionMinRound = agentNode.getReflectionMinRound();
            this.reflectionPrompt = agentNode.getReflectionPrompt();
            return this;
        }

        @Override
        public void setupNode(FlowNode<?> node) {
            this.setupNodeBase(node);
            var agentNode = (AgentFlowNode) node;
            agentNode.setSystemPrompt(this.systemPrompt);
            agentNode.setDescription(this.description);
            agentNode.setPromptTemplate(this.promptTemplate);
            agentNode.setUseGroupContext(this.useGroupContext);
            agentNode.setFormatter(this.formatter);
            agentNode.setReflectionEnabled(this.reflectionEnabled);
            agentNode.setReflectionMaxRound(this.reflectionMaxRound);
            agentNode.setReflectionMinRound(this.reflectionMinRound);
            agentNode.setReflectionPrompt(this.reflectionPrompt);
        }
    }
}
