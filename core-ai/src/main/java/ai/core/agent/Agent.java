package ai.core.agent;

import ai.core.agent.internal.AgentHelper;
import ai.core.agent.slashcommand.SlashCommandParser;
import ai.core.context.Compression;
import ai.core.defaultagents.DefaultRagQueryRewriteAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.prompt.Prompts;
import ai.core.prompt.engines.MustachePromptTemplate;
import ai.core.rag.RagConfig;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionEvaluation;
import ai.core.reflection.ReflectionEvaluator;
import ai.core.reflection.ReflectionHistory;
import ai.core.reflection.ReflectionListener;
import ai.core.reflection.ReflectionStatus;
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
import core.framework.json.JSON;
import core.framework.util.Maps;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class Agent extends Node<Agent> {
    public static AgentBuilder builder() {
        return new AgentBuilder();
    }

    private final Logger logger = LoggerFactory.getLogger(Agent.class);
    private CancellationToken rootToken;
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
    Boolean authenticated = false;
    ToolExecutor toolExecutor;
    Compression compression;
    ReasoningEffort reasoningEffort;
    List<SubAgentToolCall> subAgents = new ArrayList<>();
    // Span context of the LLM call whose response triggered the current tool execution, scoped to THIS agent.
    // Kept per-agent (not on the shared ExecutionContext) so a parent agent and its sub-agents each track
    // their own triggering LLM span and tool spans nest under the correct agent subtree.
    private volatile SpanContext lastLLMSpanContext;

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
        chatTurns(query, variables, this::constructionFakeSlashCommandAssistantMsg);
    }

    private void chatLoops(String query, Map<String, Object> variables, boolean skipReflection) {
        var prompt = promptTemplate + query;
        Map<String, Object> context = variables == null ? Maps.newConcurrentHashMap() : new HashMap<>(variables);
        if (ragConfig.useRag()) {
            rag(getInput(), context);
            prompt += RagConfig.AGENT_RAG_CONTEXT_TEMPLATE;
        }

        prompt = new MustachePromptTemplate().execute(prompt, context, Hash.md5Hex(promptTemplate));

        chatTurns(prompt, variables, this::handLLM);

        if (reflectionConfig != null && reflectionConfig.enabled() && !skipReflection) {
            reflectionLoop(variables);
        }
    }

    private String doExecute(String query, Map<String, Object> variables, boolean skipReflection) {
        boolean isFirstExecution = getInput() == null;
        if (isFirstExecution) {
            setInput(query);
            if (getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT && Prompts.CONFIRMATION_PROMPT.equalsIgnoreCase(query)) {
                authenticated = true;
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
        } else {
            chatLoops(query, variables, skipReflection);
        }
    }

    private void reflectionLoop(Map<String, Object> variables) {
        ReflectionHistory history = new ReflectionHistory(getId(), getName(), getInput(), reflectionConfig.evaluationCriteria());
        int currentRound = 1;
        setRound(currentRound);
        if (reflectionListener != null)
            reflectionListener.onReflectionStart(this, getInput(), reflectionConfig.evaluationCriteria());
        while (currentRound <= reflectionConfig.maxRound()) {
            throwIfCancelled();
            setRound(currentRound);
            Instant roundStart = Instant.now();
            String solutionToEvaluate = getOutput();
            logger.debug("Reflection round: {}/{}, agent: {}", currentRound, reflectionConfig.maxRound(), getName());
            if (reflectionListener != null) reflectionListener.onBeforeRound(this, currentRound, solutionToEvaluate);
            var evalRequest = new ReflectionEvaluator.EvaluationRequest(
                    getInput(), getOutput(), getName(), getLLMProvider(),
                    getTemperature(), getModel(), reflectionConfig, variables);
            ReflectionEvaluator.EvaluationResult evalResult = ReflectionEvaluator.evaluate(evalRequest);
            addTokenCost(evalResult.usage());
            String evaluationJson = evalResult.evaluationJson();
            ReflectionEvaluation evaluation = JSON.fromJSON(ReflectionEvaluation.class, evaluationJson);
            if (!AgentHelper.isValidEvaluation(evaluation)) {
                logger.error("Invalid evaluation score: {}, terminating reflection", evaluation.getScore());
                history.complete(ReflectionStatus.FAILED);
                if (reflectionListener != null) reflectionListener.onError(this, currentRound,
                        new IllegalStateException("Invalid evaluation score: " + evaluation.getScore()));
                return;
            }
            logger.debug("Round {} evaluation: score={}, pass={}, continue={}",
                    currentRound, evaluation.getScore(), evaluation.isPass(), evaluation.isShouldContinue());
            history.addRound(new ReflectionHistory.ReflectionRound(currentRound, solutionToEvaluate, evaluationJson,
                    evaluation, Duration.between(roundStart, Instant.now()), (long) getCurrentTokenUsage().getTotalTokens()));
            if (AgentHelper.shouldTerminateReflection(reflectionConfig, evaluation, currentRound)) {
                logger.debug("Reflection terminating: score={}, pass={}", evaluation.getScore(), evaluation.isPass());
                notifyTerminationReason(evaluation, currentRound);
                break;
            }
            doExecute(ReflectionEvaluator.buildImprovementPrompt(evaluationJson, evaluation), variables, true);
            if (reflectionListener != null)
                reflectionListener.onAfterRound(this, currentRound, getOutput(), evaluation);
            currentRound++;
        }
        if (currentRound > reflectionConfig.maxRound() && reflectionListener != null) {
            int finalScore = history.getRounds().isEmpty() ? 0 : history.getRounds().getLast().getEvaluation().getScore();
            reflectionListener.onMaxRoundsReached(this, finalScore);
        }
        history.complete(determineCompletionStatus(history));
        if (reflectionListener != null) reflectionListener.onReflectionComplete(this, history);
    }

    private void notifyTerminationReason(ReflectionEvaluation eval, int round) {
        if (reflectionListener == null) return;
        if (eval.isPass() && eval.getScore() >= 8) reflectionListener.onScoreAchieved(this, eval.getScore(), round);
        else if (!eval.isShouldContinue()) reflectionListener.onNoImprovement(this, eval.getScore(), round);
    }

    private ReflectionStatus determineCompletionStatus(ReflectionHistory history) {
        if (history.getRounds().size() >= reflectionConfig.maxRound()) return ReflectionStatus.COMPLETED_MAX_ROUNDS;
        if (history.getRounds().isEmpty()) return ReflectionStatus.COMPLETED_SUCCESS;
        var lastEval = history.getRounds().getLast().getEvaluation();
        if (lastEval.isPass() && lastEval.getScore() >= 8) return ReflectionStatus.COMPLETED_SUCCESS;
        return lastEval.isShouldContinue() ? ReflectionStatus.COMPLETED_SUCCESS : ReflectionStatus.COMPLETED_NO_IMPROVEMENT;
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
            var mat = toolRegistry.materialize();
            var turnMsgList = turn(getMessages(), mat, constructionAssistantMsg);
            logger.debug("Agent[{}] turn {}: received {} messages", getName(), currentIteCount + 1, turnMsgList.size());
            turnMsgList.forEach(this::addMessage);
            agentOut.append(turnMsgList.stream().filter(m -> RoleType.ASSISTANT.equals(m.role)).map(Message::getTextContent).collect(Collectors.joining("")));
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

    private Choice constructionFakeSlashCommandAssistantMsg(List<Message> messages, List<Tool> tools) {
        logger.debug("all tools size is {}", tools.size());
        String query = messages.getLast().getTextContent();
        var command = SlashCommandParser.parse(query);
        if (command.isNotValid()) {
            return Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "Error: Invalid slash command format. Expected: /slash_command:tool_name:arguments"));
        } else {
            var functionCall = FunctionCall.of(AgentHelper.generateToolCallId(), "function", command.getToolName(), command.hasArguments() ? command.getArguments() : "{}");
            return Choice.of(FinishReason.TOOL_CALLS, Message.of(RoleType.ASSISTANT, "", "assistant", null, List.of(functionCall)));
        }
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

    private Choice handLLM(List<Message> messages, List<Tool> tools) {
        var effectiveModel = resolveEffectiveModel(messages);
        var req = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(messages, tools, llmProvider.config == null ? 0 : llmProvider.config.getTemperature(), effectiveModel, this.getName(), null, null, reasoningEffort));
        // Reset before each LLM call; any tool spans triggered by this call will nest under it.
        this.lastLLMSpanContext = null;
        return aroundLLM(r -> llmProvider.completionStream(r, AgentHelper.elseDefaultCallback(getStreamingCallback()), sc -> this.lastLLMSpanContext = sc), req);
    }

    private String resolveEffectiveModel(List<Message> messages) {
        if (multiModalModel == null) return model;
        for (var message : messages) {
            if (message.content == null) continue;
            for (var content : message.content) {
                if (content.type == Content.ContentType.IMAGE_URL || content.type == Content.ContentType.FILE) {
                    return multiModalModel;
                }
            }
        }
        return model;
    }

    private Choice aroundLLM(Function<CompletionRequest, CompletionResponse> func, CompletionRequest request) {
        agentLifecycles.forEach(alc -> alc.beforeModel(request, getExecutionContext()));
        var resp = callLLM(func, request);

        for (var lifecycle : agentLifecycles) {
            var retryMessages = lifecycle.onModelResponse(request, resp, getExecutionContext());
            if (retryMessages != null && !retryMessages.isEmpty()) {
                retryMessages.forEach(this::addMessage);
                resp = callLLM(func, request);
                break;
            }
        }

        return resp.choices.getFirst();
    }

    private CompletionResponse callLLM(Function<CompletionRequest, CompletionResponse> func, CompletionRequest request) {
        var resp = func.apply(request);
        addTokenCost(resp.usage);
        agentLifecycles.forEach(alc -> alc.afterModel(request, resp, getExecutionContext()));
        return resp;
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
                && !lastMsg.toolCalls.isEmpty() && reqMsg.role != RoleType.TOOL) {
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
        if (ragConfig.vectorStore() == null || ragConfig.llmProvider() == null)
            throw new RuntimeException("vectorStore/llmProvider cannot be null if useRag flag is enabled");
        var ragQuery = query;
        if (ragConfig.enableQueryRewriting()) {
            ragQuery = DefaultRagQueryRewriteAgent.of(ragConfig.llmProvider()).run(query);
        }
        var rsp = ragConfig.llmProvider().embeddings(new EmbeddingRequest(List.of(ragQuery)));
        addTokenCost(rsp.usage);
        var embedding = rsp.embeddings.getFirst().embedding;
        var docs = ragConfig.vectorStore().similaritySearch(SimilaritySearchRequest.builder()
                .embedding(embedding)
                .threshold(ragConfig.threshold())
                .topK(ragConfig.topK()).build());
        var context = ragConfig.llmProvider().rerankings(RerankingRequest.of(ragQuery, docs.stream().map(v -> v.content).toList())).rerankedDocuments.getFirst();
        variables.put(RagConfig.AGENT_RAG_CONTEXT_PLACEHOLDER, context);
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

        if (isInterruptionMarker(messages.getLast())) {
            logger.debug("detected persisted interruption marker in history");
        }

        addMessages(messages);
    }

    public String continueWithInjectedMessage() {
        return runTurnsLoop(this::handLLM);
    }

    public CancellationToken getCancellationToken() {
        if (rootToken == null) {
            rootToken = CancellationToken.create();
        }
        return rootToken;
    }

    public void cancel() {
        boolean alreadyCancelled = getCancellationToken().isCancelled();
        getCancellationToken().cancel();
        if (!alreadyCancelled) {
            injectInterruptionMarker();
            persistInterruptionMarkerIfExists();
        }
    }

    public void resetCancellation() {
        getCancellationToken().reset();
    }

    public boolean isCancelled() {
        return getExecutionContext().isCancelled();
    }

    private void throwIfCancelled() {
        getExecutionContext().throwIfCancelled();
    }

    private void injectInterruptionMarker() {
        var token = getCancellationToken();
        var reason = token.getReason();
        if (!shouldInjectMarker(reason)) return;

        var marker = reason == CancelReason.USER_CANCELLED
                ? "<system-reminder>The user interrupted the previous action. Do not continue what you were doing.</system-reminder>"
                : reason == CancelReason.REPLACED
                ? "<system-reminder>The previous turn was replaced by a new request. The results above may be incomplete.</system-reminder>"
                : "<system-reminder>The previous action was cancelled (reason: " + reason.name().toLowerCase() + ").</system-reminder>";

        addMessage(Message.of(RoleType.USER, marker));
    }

    private static boolean shouldInjectMarker(CancelReason reason) {
        return reason == CancelReason.USER_CANCELLED
                || reason == CancelReason.REPLACED
                || reason == CancelReason.TIMEOUT;
    }

    private void persistInterruptionMarkerIfExists() {
        if (!hasPersistenceProvider()) return;
        var sessionId = getExecutionContext().getSessionId();
        if (sessionId != null) {
            save(sessionId);
        }
    }

    private static boolean isInterruptionMarker(Message msg) {
        if (msg.role != RoleType.USER) return false;
        var text = msg.getTextContent();
        return text != null && text.startsWith("<system-reminder>The previous");
    }

    private void normalizeMessages() {
        var messages = getMessages();
        java.util.Set<String> toolResultIds = new java.util.HashSet<>();
        java.util.Set<String> orphanToolUses = new java.util.LinkedHashSet<>();

        for (var msg : messages) {
            if (msg.role == RoleType.TOOL && msg.toolCallId != null) {
                toolResultIds.add(msg.toolCallId);
            }
        }
        for (var msg : messages) {
            if (msg.role == RoleType.ASSISTANT && msg.toolCalls != null) {
                for (var tc : msg.toolCalls) {
                    if (!toolResultIds.contains(tc.id)) {
                        orphanToolUses.add(tc.id);
                    }
                }
            }
        }

        for (var orphanId : orphanToolUses) {
            addMessage(Message.of(RoleType.TOOL,
                    "Error: Tool execution was cancelled or interrupted.",
                    "system", orphanId, null));
        }
    }

    @Override
    public ExecutionContext getExecutionContext() {
        var context = super.getExecutionContext();
        if (context.getCancellationToken() == null) {
            context.setCancellationToken(getCancellationToken());
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
