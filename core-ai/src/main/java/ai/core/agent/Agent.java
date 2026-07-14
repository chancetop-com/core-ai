package ai.core.agent;

import ai.core.agent.atmention.AtMentionParser;
import ai.core.agent.internal.AgentHelper;
import ai.core.agent.slashcommand.SlashCommandParser;
import ai.core.context.Compression;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.prompt.Prompts;
import ai.core.prompt.engines.MustachePromptTemplate;
import ai.core.rag.RagConfig;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionListener;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.context.AgentTraceContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolExecutor;
import ai.core.tool.ToolOrchestration;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolMaterialization;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.tools.SubAgentToolCall;
import core.framework.crypto.Hash;
import core.framework.util.Maps;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class Agent extends Node<Agent> {
    public static AgentBuilder builder() {
        return new AgentBuilder();
    }

    final Logger logger = LoggerFactory.getLogger(Agent.class);
    CancellationToken rootToken;
    String systemPrompt;
    String promptTemplate;
    LLMProvider llmProvider;
    ToolRegistry toolRegistry;
    RagConfig ragConfig;
    Double temperature;
    String model;
    String multiModalModel;
    ReflectionConfig reflectionConfig;
    ReflectionListener reflectionListener;
    Boolean useGroupContext;
    Integer maxTurnNumber;
    Boolean authenticated = Boolean.FALSE;
    ToolExecutor toolExecutor;
    Compression compression;
    ReasoningEffort reasoningEffort;
    List<SubAgentToolCall> subAgents = new ArrayList<>();
    // Span context of the LLM call whose response triggered the current tool execution, scoped to THIS agent.
    // Kept per-agent (not on the shared ExecutionContext) so a parent agent and its sub-agents each track
    // their own triggering LLM span and tool spans nest under the correct agent subtree.
    volatile SpanContext lastLLMSpanContext;

    @Override
    String execute(String query, Map<String, Object> variables) {
        var activeTracer = (AgentTracer) getTracer();
        if (activeTracer != null) {
            var execContext = getExecutionContext();
            var context = AgentTraceContext.builder()
                    .name(getName())
                    .id(getId())
                    .input(query)
                    .withTools(toolRegistry != null && !toolRegistry.getToolCalls().isEmpty())
                    .withRag(ragConfig != null && ragConfig.useRag())
                    .sessionId(execContext.getSessionId())
                    .userId(execContext.getUserId())
                    .build();

            return activeTracer.traceAgentExecution(context, () -> {
                var result = doExecute(query, variables, false);
                context.setOutput(getOutput());
                context.setStatus(getNodeStatus().name());
                context.setMessageCount(getMessages().size());
                return result;
            }, this::isCancelled);
        }
        return doExecute(query, variables, false);
    }

    private void chatCommand(String query, Map<String, Object> variables) {
        chatTurns(query, variables, SyntheticMessageFactory.wrapFirstTurn(this, (m, t) -> SyntheticMessageFactory.constructionFakeSlashCommandAssistantMsg(this, m, t)));
    }

    private void chatLoops(String query, Map<String, Object> variables, boolean skipReflection) {
        var prompt = promptTemplate + query;
        Map<String, Object> context = variables == null ? Maps.newConcurrentHashMap() : new HashMap<>(variables);
        if (ragConfig.useRag()) {
            rag(getInput(), context);
            prompt += RagConfig.AGENT_RAG_CONTEXT_TEMPLATE;
        }

        prompt = new MustachePromptTemplate().execute(prompt, context, Hash.md5Hex(promptTemplate));

        chatTurns(prompt, variables, (m, t) -> ModelGateway.handLLM(this, m, t));

        if (reflectionConfig != null && !skipReflection && reflectionConfig.enabled()) {
            ReflectionOrchestrator.reflectionLoop(this, variables);
        }
    }

    String doExecute(String query, Map<String, Object> variables, boolean skipReflection) {
        boolean isFirstExecution = getInput() == null;
        if (isFirstExecution) {
            setInput(query);
            if (getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT && Prompts.CONFIRMATION_PROMPT.equalsIgnoreCase(query)) {
                authenticated = Boolean.TRUE;
            }
            updateNodeStatus(NodeStatus.RUNNING);
        }
        commandOrLoops(query, variables, skipReflection);
        if (isFirstExecution && getNodeStatus() == NodeStatus.RUNNING) {
            updateNodeStatus(NodeStatus.COMPLETED);
        }
        return getOutput();
    }

    private void commandOrLoops(String query, Map<String, Object> variables, boolean skipReflection) {
        if (SlashCommandParser.isSlashCommand(query)) {
            chatCommand(query, variables);
        } else if (isAtMention(query)) {
            chatAtMention(query, variables);
        } else {
            chatLoops(query, variables, skipReflection);
        }
    }

    private boolean isAtMention(String query) {
        return AtMentionParser.isAtMention(query) && getExecutionContext().getAgentProfileRegistry() != null;
    }

    private void chatAtMention(String query, Map<String, Object> variables) {
        chatTurns(query, variables, SyntheticMessageFactory.wrapFirstTurn(this, (m, t) -> SyntheticMessageFactory.constructionFakeAtMentionAssistantMsg(this, m)));
    }

    protected void chatTurns(String query, Map<String, Object> variables, BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
        buildUserQueryToMessage(query, variables);
        runTurnsLoop(constructionAssistantMsg);
    }

    private String runTurnsLoop(BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
        var currentIteCount = 0;
        var agentOut = new StringBuilder();
        do {
            if (isCancelled()) break;
            var mat = toolRegistry.materialize(getExecutionContext());
            var turnMsgList = turn(getMessages(), mat, constructionAssistantMsg);
            logger.debug("Agent[{}] turn {}: received {} messages", getName(), currentIteCount + 1, turnMsgList.size());
            turnMsgList.forEach(this::addMessage);
            var turnText = turnMsgList.stream().filter(m -> RoleType.ASSISTANT.equals(m.role)).map(Message::getTextContent).collect(Collectors.joining(""));
            // insert a paragraph break between turns that produce text,
            // so persisted chat history and page reloads display each text output separately
            if (!turnText.isEmpty()) {
                if (!agentOut.isEmpty()) {
                    agentOut.append("\n\n");
                }
                agentOut.append(turnText);
            }
            currentIteCount++;
        } while (!isCancelled()
                && AgentHelper.lastIsToolMsg(getMessages())
                && currentIteCount < maxTurnNumber);

        setOutput(agentOut.toString());
        if (currentIteCount >= maxTurnNumber) {
            logger.warn("agent run out of turns: maxTurnNumber - {}", maxTurnNumber);
            throw new MaxTurnsExceededException(maxTurnNumber);
        }
        return agentOut.toString();
    }

    public List<Message> turn(List<Message> messages, ToolMaterialization toolMaterialization, BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
        var resultMsg = new ArrayList<Message>();
        var choice = constructionAssistantMsg.apply(messages, toolMaterialization.definitions());
        resultMsg.add(choice.message.toMessage());
        if (choice.finishReason == FinishReason.TOOL_CALLS) {
            var funcMsg = handleFunc(choice.message.toMessage(), toolMaterialization.getDispatchMap());
            resultMsg.addAll(funcMsg);
        }
        return resultMsg;
    }

    public List<Message> handleFunc(Message funcMsg, Map<String, ToolCall> dispatchMap) {
        if (isCancelled()) return List.of();
        var orchestration = new ToolOrchestration(dispatchMap, agentLifecycles, getToolExecutor(), getExecutionContext());
        return orchestration.execute(funcMsg.toolCalls);
    }

    private ToolExecutor getToolExecutor() {
        if (toolExecutor == null) {
            toolExecutor = new ToolExecutor(agentLifecycles, getTracer(), this::updateNodeStatus, () -> this.lastLLMSpanContext);
        }
        toolExecutor.setAuthenticated(authenticated);
        return toolExecutor;
    }

    private void buildUserQueryToMessage(String query, Map<String, Object> variables) {
        normalizeMessages();

        if (getMessages().isEmpty()) {
            addMessage(buildSystemMessage(variables));
        } else if (getMessages().getFirst().role != RoleType.SYSTEM) {
            // history restored without a system message (e.g. session rebuilt from DB) — prepend it
            addMessageToFront(buildSystemMessage(variables));
        }

        if (isUseGroupContext() && getParentNode() != null) {
            addMessages(getParentNode().getMessages());
        }

        var reqMsg = AgentHelper.buildUserMessage(query, getExecutionContext());
        removeLastAssistantToolCallMessageIfNotToolResult(reqMsg);
        addMessage(reqMsg);
    }

    private void removeLastAssistantToolCallMessageIfNotToolResult(Message reqMsg) {
        var lastMsg = getMessages().getLast();
        if (lastMsg.role == RoleType.ASSISTANT && lastMsg.toolCalls != null
                && reqMsg.role != RoleType.TOOL && !lastMsg.toolCalls.isEmpty()) {
            removeMessage(lastMsg);
        }
    }

    @Override
    void setChildrenParentNode() {
    }

    private Message buildSystemMessage(Map<String, Object> variables) {
        var prompt = systemPrompt;
        if (getParentNode() != null && isUseGroupContext()) {
            this.putSystemVariable(getParentNode().getSystemVariables());
        }
        Map<String, Object> var = Maps.newConcurrentHashMap();
        if (variables != null) var.putAll(variables);
        var.putAll(getSystemVariables());
        prompt = new MustachePromptTemplate().execute(prompt, var, Hash.md5Hex(promptTemplate));
        return Message.of(RoleType.SYSTEM, prompt);
    }

    private void rag(String query, Map<String, Object> variables) {
        RagPipeline.execute(ragConfig, query, variables, this::addTokenCost);
    }

    public Boolean isUseGroupContext() {
        return this.useGroupContext;
    }

    public List<ToolCall> getToolCalls() {
        return toolRegistry.getToolCalls();
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public String getModel() {
        return model;
    }

    public String getMultiModalModel() {
        return multiModalModel;
    }

    public void setMultiModalModel(String multiModalModel) {
        this.multiModalModel = multiModalModel;
    }

    public LLMProvider getLLMProvider() {
        return llmProvider;
    }

    public Compression getCompression() {
        return compression;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public void setLlmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public List<SubAgentToolCall> getSubAgents() {
        return subAgents;
    }

    void setSubAgents(List<SubAgentToolCall> subAgents) {
        this.subAgents = subAgents;
    }

    public boolean hasSubAgents() {
        return subAgents != null && !subAgents.isEmpty();
    }

    public SubAgentToolCall toSubAgentToolCall() {
        return SubAgentToolCall.builder().subAgent(this).build();
    }

    public SubAgentToolCall toSubAgentToolCall(Class<?>... classes) {
        return SubAgentToolCall.builder().subAgent(this, classes).build();
    }

    public void addTools(List<ToolCall> tools) {
        if (tools == null || tools.isEmpty() || toolRegistry == null) {
            logger.warn("No tools to register");
            return;
        }
        for (var tc : tools) {
            toolRegistry.registerProvider(ListToolProvider.of(tc.getName(), List.of(tc)));
        }
    }

    public void injectUserMessage(String content) {
        addMessage(Message.of(RoleType.USER, content));
    }

    // restore prior conversation messages on session rebuild — only user/assistant text, skip thinking/tools
    public void restoreHistory(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        if (AgentInterruptionHandler.isInterruptionMarker(messages.getLast())) {
            logger.debug("detected persisted interruption marker in history");
        }

        addMessages(messages);
    }

    public String continueWithInjectedMessage() {
        return runTurnsLoop((m, t) -> ModelGateway.handLLM(this, m, t));
    }

    public void cancel() {
        AgentInterruptionHandler.cancel(this);
    }

    public CancellationToken getCancellationToken() {
        return AgentInterruptionHandler.getCancellationToken(this);
    }

    public void resetCancellation() {
        AgentInterruptionHandler.getCancellationToken(this).reset();
    }

    public boolean isCancelled() {
        return getExecutionContext().isCancelled();
    }

    private void normalizeMessages() {
        MessageNormalizer.normalize(this);
    }

    @Override
    public ExecutionContext getExecutionContext() {
        var context = super.getExecutionContext();
        if (context.getCancellationToken() == null) {
            context.setCancellationToken(AgentInterruptionHandler.getCancellationToken(this));
        }
        context.setLlmProvider(llmProvider);
        context.setModel(model);
        context.setMultiModalModel(multiModalModel);
        context.setStreamingCallback(getStreamingCallback());
        context.setLifecycles(agentLifecycles);
        context.setToolRegistry(toolRegistry);
        return context;
    }
}
