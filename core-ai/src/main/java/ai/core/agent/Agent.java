package ai.core.agent;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.memory.memories.LongTernMemory;
import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.prompt.engines.MustachePromptTemplate;
import ai.core.rag.RagConfig;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.reflection.ReflectionConfig;
import ai.core.termination.terminations.MaxRoundTermination;
import ai.core.termination.terminations.StopMessageTermination;
import ai.core.tool.ToolCall;
import core.framework.crypto.Hash;
import core.framework.json.JSON;
import core.framework.util.Lists;
import core.framework.util.Maps;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class Agent extends Node<Agent> {
    public static Builder builder() {
        return new Builder();
    }
    private final Logger logger = LoggerFactory.getLogger(Agent.class);

    String systemPrompt;
    String promptTemplate;
    LLMProvider llmProvider;
    List<ToolCall> toolCalls;
    RagConfig ragConfig;
    Double temperature;
    String model;
    ReflectionConfig reflectionConfig;
    LongTernMemory longTernMemory;
    Boolean useGroupContext;
    Integer maxToolCallCount;
    Integer currentToolCallCount;
    Boolean authenticated = false;

    @Override
    String execute(String query, Map<String, Object> variables) {
        setupAgentSystemVariables();
        setInput(query);

        var prompt = promptTemplate + query;
        Map<String, Object> context = variables == null ? Maps.newConcurrentHashMap() : new HashMap<>(variables);
        // add context if rag is enabled
        if (ragConfig.useRag()) {
            rag(query, context);
            prompt += RagConfig.AGENT_RAG_CONTEXT_TEMPLATE;
        }

        // compile and execute template
        prompt = new MustachePromptTemplate().execute(prompt, context, Hash.md5Hex(promptTemplate));

        if (currentTokenUsageOutOfMax(prompt, llmProvider.maxTokens())) {
            prompt = handleToShortQuery(prompt, null);
        }

        // set authenticated flag if the agent is authenticated
        if (getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT && Prompts.CONFIRMATION_PROMPT.equalsIgnoreCase(query)) {
            authenticated = true;
        }

        // update chain node status
        updateNodeStatus(NodeStatus.RUNNING);

        // chat with LLM the first time
        chat(prompt, variables);

        // reflection if enabled
        if (reflectionConfig != null && reflectionConfig.enabled()) {
            reflection(variables);
        }

        // nothing wrong, update node status to completed, otherwise it had been set by the subsequent chat or reflection
        if (getNodeStatus() == NodeStatus.RUNNING) updateNodeStatus(NodeStatus.COMPLETED);
        return getOutput();
    }

    private void setupAgentSystemVariables() {
    }

    private void chat(String query, Map<String, Object> variables) {
        // call LLM completion
        var rst = chatByUser(query, variables);

        // we always use the first choice
        var choice = getChoice(rst);
        setMessageAgentInfo(choice.message);

        // add rsp to message list and call message event listener if it has
        addMessage(choice.message);
        setOutput(choice.message.content);

        // function call loop
        if (choice.finishReason == FinishReason.TOOL_CALLS) {
            currentToolCallCount = 0;
            chatByToolCall(choice);
        }
    }

    private void reflection(Map<String, Object> variables) {
        // never start if we do not have termination or something
        validation();
        setRound(1);
        while (notTerminated()) {
            logger.info("reflection round: {}/{}, agent: {}, input: {}, output: {}", getRound(), getMaxRound(), getName(), getInput(), getOutput());
            chat(reflectionConfig.prompt(), variables);
            setRound(getRound() + 1);
        }
    }

    private CompletionResponse chatByUser(String query, Map<String, Object> variables) {
        buildUserQueryToMessage(query, variables);
        return completionWithFormat();
    }

    private void chatByToolCall(Choice toolChoice) {
        currentToolCallCount += 1;
        var callRst = toolChoice.message.toolCalls.stream().map(this::functionCall).toList();
        // send the tool call result to the LLM
        var rst = chatByToolCall(callRst, toolChoice.message.toolCalls);

        // we always use the first choice
        var choice = getChoice(rst);
        setMessageAgentInfo(choice.message);

        // add rsp to message list and call message event listener if it has
        addMessage(choice.message);
        setOutput(choice.message.content);

        // function call loop
        if (choice.finishReason == FinishReason.TOOL_CALLS && currentToolCallCount < maxToolCallCount) {
            chatByToolCall(choice);
        }
    }

    private CompletionResponse chatByToolCall(List<String> toolResult, List<FunctionCall> toolCalls) {
        buildToolResultToMessage(toolResult, toolCalls);
        return completionWithFormat();
    }

    private void buildUserQueryToMessage(String query, Map<String, Object> variables) {
        if (getMessages().isEmpty()) {
            addMessage(buildSystemMessageWithLongTernMemory(variables));
            // add task context if existed
            addTaskHistoriesToMessages();
        }

        // add group context if needed
        if (isUseGroupContext() && getParentNode() != null) {
            addMessages(getParentNode().getMessages());
        }

        var reqMsg = Message.of(RoleType.USER, query, buildRequestName(false), null, null, null);
        removeLastAssistantToolCallMessageIfNotToolResult(reqMsg);
        addMessage(reqMsg);
    }

    private void buildToolResultToMessage(List<String> toolResult, List<FunctionCall> toolCalls) {
        if (toolResult.size() != toolCalls.size()) {
            throw new ConflictException("Tool calls size must match query size, toolCalls: " + toolCalls.size() + ", query: " + toolResult.size(), "TOOL_CALLS_SIZE_MISMATCH");
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            var reqMsg = Message.of(RoleType.TOOL, toolResult.get(i), buildRequestName(true), toolCalls.get(i).id, null, null);
            addMessage(reqMsg);
        }
    }

    private CompletionResponse completionWithFormat() {
        var req = CompletionRequest.of(getMessages(), toReqTools(this.toolCalls), temperature, model, this.getName());

        // completion with llm provider
        var rst = llmProvider.completion(req);

        addTokenCost(rst.usage);
        setRawOutput(rst.choices.getFirst().message.content);

        // remove think content
        if (withThinkContent(rst)) {
            removeThinkContent(rst);
        }
        // format the LLM response
        if (getAgentFormatter() != null) {
            formatContent(rst, getAgentFormatter());
        }

        // cleanup messages if exceed the limit
        if (rst.usage.getTotalTokens() > llmProvider.maxTokens()) {
            throw new RuntimeException("Exceed the max tokens limit");
        }

        return rst;
    }

    private List<Tool> toReqTools(List<ToolCall> toolCalls) {
        return toolCalls.stream().map(ToolCall::toTool).toList();
    }

    private void removeLastAssistantToolCallMessageIfNotToolResult(Message reqMsg) {
        var lastMessage = getMessages().getLast();
        if (lastMessage.role == RoleType.ASSISTANT
                && lastMessage.toolCalls != null
                && !lastMessage.toolCalls.isEmpty()
                && reqMsg.role != RoleType.TOOL) {
            removeMessage(lastMessage);
        }
    }

    private String buildRequestName(boolean isToolCall) {
        return isToolCall ? "tool" : "user";
    }

    private void setMessageAgentInfo(Message msg) {
        msg.setAgentName(getName());
        if (getParentNode() != null) {
            msg.setGroupName(getParentNode().getName());
        }
    }

    @Override
    void setChildrenParentNode() {
    }

    private Choice getChoice(CompletionResponse rst) {
        return rst.choices.getFirst();
    }

    private Message buildSystemMessageWithLongTernMemory(Map<String, Object> variables) {
        var prompt = systemPrompt;
        if (getParentNode() != null && isUseGroupContext()) {
            this.putSystemVariable(getParentNode().getSystemVariables());
        }
        Map<String, Object> var = Maps.newConcurrentHashMap();
        if (variables != null) var.putAll(variables);
        var.putAll(getSystemVariables());
        prompt = new MustachePromptTemplate().execute(prompt, var, Hash.md5Hex(promptTemplate));
        if (!getLongTernMemory().isEmpty()) {
            prompt += LongTernMemory.TEMPLATE + getLongTernMemory().toString();
        }
        return Message.of(RoleType.SYSTEM, prompt, getName());
    }

    private String functionCall(FunctionCall toolCall) {
        var optional = toolCalls.stream().filter(v -> v.getName().equalsIgnoreCase(toolCall.function.name)).findFirst();
        if (optional.isEmpty()) {
            throw new BadRequestException("tool call failed<optional empty>: " + JSON.toJSON(toolCall), "TOOL_CALL_FAILED");
        }
        var function = optional.get();
        try {
            if (function.isNeedAuth() && !authenticated) {
                this.updateNodeStatus(NodeStatus.WAITING_FOR_USER_INPUT);
                return "This tool call requires user authentication, please ask user to confirm it.";
            }
            logger.info("function call {}: {}", toolCall.function.name, toolCall.function.arguments);
            return function.call(toolCall.function.arguments);
        } catch (Exception e) {
            throw new BadRequestException(Strings.format("tool call failed<execute>:\n{}, cause:\n{}", JSON.toJSON(toolCall), e.getMessage()), "TOOL_CALL_FAILED", e);
        }
    }

    private void rag(String query, Map<String, Object> variables) {
        if (ragConfig.vectorStore() == null) throw new RuntimeException("vectorStore cannot be null if useRag flag is enabled");
        var rsp = llmProvider.embeddings(new EmbeddingRequest(List.of(query)));
        addTokenCost(rsp.usage);
        var embedding = rsp.embeddings.getFirst().embedding;
        var context = ragConfig.vectorStore().similaritySearchText(SimilaritySearchRequest.builder()
                .embedding(embedding)
                .threshold(ragConfig.threshold())
                .topK(ragConfig.topK()).build());
        variables.put(RagConfig.AGENT_RAG_CONTEXT_PLACEHOLDER, context);
    }

    private void validation() {
        if (getTerminations().isEmpty()) {
            throw new RuntimeException(Strings.format("Reflection agent must have termination: {}<{}>", getName(), getId()));
        }
    }

    private boolean withThinkContent(CompletionResponse response) {
        return !response.choices.isEmpty()
                && response.choices.getFirst().message != null
                && response.choices.getFirst().message.content != null
                && response.choices.getFirst().message.content.contains("<think>");
    }

    private void removeThinkContent(CompletionResponse response) {
        var text = response.choices.getFirst().message.content;
        logger.info("think: {}", text.substring(0, text.indexOf("</think>") + 8));
        response.choices.getFirst().message.content = text.substring(text.lastIndexOf("</think>") + 8);
    }

    public Boolean isUseGroupContext() {
        return this.useGroupContext;
    }

    public ReflectionConfig getReflectionConfig() {
        return this.reflectionConfig;
    }

    public List<? extends ToolCall> getToolCalls() {
        return this.toolCalls;
    }

    public LongTernMemory getLongTernMemory() {
        return this.longTernMemory;
    }

    public void addLongTernMemory(String memory) {
        this.longTernMemory.add(memory);
    }

    public void clearLongTernMemory() {
        this.longTernMemory.clear();
    }

    public void setModel(String model) {
        this.model = model;
    }

    public static class Builder extends NodeBuilder<Builder, Agent> {
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

        public Builder promptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder maxToolCallCount(Integer maxToolCallCount) {
            this.maxToolCallCount = maxToolCallCount;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder llmProvider(LLMProvider llmProvider) {
            this.llmProvider = llmProvider;
            return this;
        }

        public Builder toolCalls(List<? extends ToolCall> toolCalls) {
            this.toolCalls = new ArrayList<>(toolCalls);
            return this;
        }

        public Builder ragConfig(RagConfig ragConfig) {
            this.ragConfig = ragConfig;
            return this;
        }

        public Builder reflectionConfig(ReflectionConfig config) {
            this.reflectionConfig = config;
            return this;
        }

        public Builder useGroupContext(Boolean useGroupContext) {
            this.useGroupContext = useGroupContext;
            return this;
        }

        public Builder enableReflection(Boolean enableReflection) {
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
                agent.longTernMemory = new LongTernMemory();
            }

            var systemVariables = agent.getSystemVariables();
            systemVariables.put(SystemVariables.AGENT_TOOLS, toolCalls.stream().map(ToolCall::toString).collect(Collectors.joining(";")));
            return agent;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
