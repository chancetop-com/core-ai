package ai.core.agent;

import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.agent.slidingwindow.SlidingWindowService;
import ai.core.agent.streaming.DefaultStreamingCallback;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.defaultagents.DefaultRagQueryRewriteAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.memory.ShortTermMemory;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
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
import core.framework.crypto.Hash;
import core.framework.json.JSON;
import core.framework.util.Maps;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    String systemPrompt;
    String promptTemplate;
    LLMProvider llmProvider;
    List<ToolCall> toolCalls;
    RagConfig ragConfig;
    Double temperature;
    String model;
    ReflectionConfig reflectionConfig;
    ReflectionListener reflectionListener;
    Boolean useGroupContext;
    Integer maxTurnNumber;
    Boolean authenticated = false;
    ToolExecutor toolExecutor;
    SlidingWindowConfig slidingWindowConfig;
    ShortTermMemory shortTermMemory;
    private SlidingWindowService slidingWindow;

    @Override
    String execute(String query, Map<String, Object> variables) {
        var activeTracer = getActiveTracer();
        if (activeTracer != null) {
            var execContext = getExecutionContext();
            var context = AgentTraceContext.builder()
                    .name(getName())
                    .id(getId())
                    .input(query)
                    .withTools(toolCalls != null && !toolCalls.isEmpty())
                    .withRag(ragConfig != null && ragConfig.useRag())
                    .sessionId(execContext.getSessionId())
                    .userId(execContext.getUserId())
                    .build();

            return activeTracer.traceAgentExecution(context, () -> {
                var result = doExecute(query, variables);
                // Update context with execution results for tracing
                context.setOutput(getOutput());
                context.setStatus(getNodeStatus().name());
                context.setMessageCount(getMessages().size());
                return result;
            });
        }
        return doExecute(query, variables);
    }

    /**
     * Get the agent's tracer if available
     * LLM tracing is handled automatically by LLMProvider.completion()
     */
    private AgentTracer getActiveTracer() {
        return getTracer();
    }

    private String doExecute(String query, Map<String, Object> variables) {
        return doExecute(query, variables, false);
    }

    private String doExecute(String query, Map<String, Object> variables, boolean skipReflection) {
        // Initialize execution (only once - saves original query)
        boolean isFirstExecution = getInput() == null;
        if (isFirstExecution) {
            setInput(query);
            if (getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT && Prompts.CONFIRMATION_PROMPT.equalsIgnoreCase(query)) {
                authenticated = true;
            }
            updateNodeStatus(NodeStatus.RUNNING);
        }

        // Execute full agent flow: RAG + template + chat
        var prompt = promptTemplate + query;
        Map<String, Object> context = variables == null ? Maps.newConcurrentHashMap() : new HashMap<>(variables);

        // RAG: Always use original input for semantic search, not improvement prompt
        if (ragConfig.useRag()) {
            rag(getInput(), context);  // Use original query for RAG
            prompt += RagConfig.AGENT_RAG_CONTEXT_TEMPLATE;
        }

        // Compile and execute template
        prompt = new MustachePromptTemplate().execute(prompt, context, Hash.md5Hex(promptTemplate));

        // Chat with LLM
        chatTurns(prompt, variables);

        // Reflection loop if enabled and not skipped
        if (reflectionConfig != null && reflectionConfig.enabled() && !skipReflection) {
            reflectionLoop(variables);
        }

        // Update status to completed only for first execution
        if (isFirstExecution && getNodeStatus() == NodeStatus.RUNNING) {
            updateNodeStatus(NodeStatus.COMPLETED);
        }
        return getOutput();
    }

    // Execute reflection loop to iteratively improve the solution
    private void reflectionLoop(Map<String, Object> variables) {
        ReflectionHistory history = new ReflectionHistory(getId(), getName(), getInput(), reflectionConfig.evaluationCriteria());
        int currentRound = 1;
        setRound(currentRound);  // Initialize round counter
        if (reflectionListener != null) reflectionListener.onReflectionStart(this, getInput(), reflectionConfig.evaluationCriteria());

        while (currentRound <= reflectionConfig.maxRound()) {
            setRound(currentRound);  // Update round counter
            Instant roundStart = Instant.now();
            String solutionToEvaluate = getOutput();
            logger.info("Reflection round: {}/{}, agent: {}", currentRound, reflectionConfig.maxRound(), getName());

            if (reflectionListener != null) reflectionListener.onBeforeRound(this, currentRound, solutionToEvaluate);

            // Evaluate using independent evaluator
            var evalRequest = new ReflectionEvaluator.EvaluationRequest(
                    getInput(), getOutput(), getName(), getLLMProvider(),
                    getTemperature(), getModel(), reflectionConfig, variables);
            ReflectionEvaluator.EvaluationResult evalResult = ReflectionEvaluator.evaluate(evalRequest);
            addTokenCost(evalResult.usage());
            String evaluationJson = evalResult.evaluationJson();
            ReflectionEvaluation evaluation = JSON.fromJSON(ReflectionEvaluation.class, evaluationJson);

            // Validate evaluation score
            if (!isValidEvaluation(evaluation)) {
                logger.error("Invalid evaluation score: {}, terminating reflection", evaluation.getScore());
                history.complete(ReflectionStatus.FAILED);
                if (reflectionListener != null) reflectionListener.onError(this, currentRound,
                    new IllegalStateException("Invalid evaluation score: " + evaluation.getScore()));
                return;
            }

            logger.info("Round {} evaluation: score={}, pass={}, continue={}",
                currentRound, evaluation.getScore(), evaluation.isPass(), evaluation.isShouldContinue());

            // Record round
            history.addRound(new ReflectionHistory.ReflectionRound(currentRound, solutionToEvaluate, evaluationJson,
                evaluation, Duration.between(roundStart, Instant.now()), (long) getCurrentTokenUsage().getTotalTokens()));

            // Check termination
            if (shouldTerminateReflection(evaluation, currentRound)) {
                logger.info("Reflection terminating: score={}, pass={}", evaluation.getScore(), evaluation.isPass());
                notifyTerminationReason(evaluation, currentRound);
                break;
            }

            // Regenerate solution and skip reflection loop (to avoid infinite recursion)
            doExecute(ReflectionEvaluator.buildImprovementPrompt(evaluationJson, evaluation), variables, true);
            if (reflectionListener != null) reflectionListener.onAfterRound(this, currentRound, getOutput(), evaluation);
            currentRound++;
        }

        if (currentRound > reflectionConfig.maxRound() && reflectionListener != null) {
            int finalScore = history.getRounds().isEmpty() ? 0 : history.getRounds().getLast().getEvaluation().getScore();
            reflectionListener.onMaxRoundsReached(this, finalScore);
        }

        history.complete(determineCompletionStatus(history));
        if (reflectionListener != null) reflectionListener.onReflectionComplete(this, history);
    }

    private boolean isValidEvaluation(ReflectionEvaluation evaluation) {
        return evaluation.getScore() >= 1 && evaluation.getScore() <= 10;
    }

    private boolean shouldTerminateReflection(ReflectionEvaluation eval, int round) {
        if (eval.isPass() && eval.getScore() >= 8) return true;
        if (!eval.isShouldContinue()) return true;
        return round >= reflectionConfig.minRound() && eval.getScore() >= 8;
    }

    private void notifyTerminationReason(ReflectionEvaluation evaluation, int currentRound) {
        if (reflectionListener == null) return;

        if (evaluation.isPass() && evaluation.getScore() >= 8) {
            reflectionListener.onScoreAchieved(this, evaluation.getScore(), currentRound);
        } else if (!evaluation.isShouldContinue()) {
            reflectionListener.onNoImprovement(this, evaluation.getScore(), currentRound);
        }
    }

    private ReflectionStatus determineCompletionStatus(ReflectionHistory history) {
        int completedRounds = history.getRounds().size();
        if (completedRounds >= reflectionConfig.maxRound()) return ReflectionStatus.COMPLETED_MAX_ROUNDS;

        if (!history.getRounds().isEmpty()) {
            ReflectionEvaluation lastEval = history.getRounds().getLast().getEvaluation();
            if (lastEval.isPass() && lastEval.getScore() >= 8) return ReflectionStatus.COMPLETED_SUCCESS;
            if (!lastEval.isShouldContinue()) return ReflectionStatus.COMPLETED_NO_IMPROVEMENT;
        }
        return ReflectionStatus.COMPLETED_SUCCESS;
    }

    // Public method for chat execution (used by executeAgentFlow)
    public void chatTurns(String query, Map<String, Object> variables) {
        buildUserQueryToMessage(query, variables);
        var currentIteCount = 0;
        var agentOut = new StringBuilder();
        do {
            // apply sliding window if needed before each LLM call
            applySlidingWindowIfNeeded();
            // turn = call model + call func
            var turnMsgList = turn(getMessages(), toReqTools(toolCalls), model);
            logger.info("Agent turn {}: received {} messages", currentIteCount + 1, turnMsgList.size());
            // add msg into global
            turnMsgList.forEach(this::addMessage);
            // include agent loop msg ,but not tool msg
            // fix Optional[]
            agentOut.append(turnMsgList.stream().filter(m -> RoleType.ASSISTANT.equals(m.role)).map(m -> m.content).collect(Collectors.joining("")));
            currentIteCount++;
        } while (lastIsToolMsg() && currentIteCount < maxTurnNumber);
        // set out
        setOutput(agentOut.toString());
        if (currentIteCount >= maxTurnNumber) {
            logger.warn("agent run out of turns: maxTurnNumber - {}", maxTurnNumber);
        }
    }

    private void applySlidingWindowIfNeeded() {
        if (slidingWindowConfig == null) return;
        if (slidingWindow == null) {
            slidingWindow = new SlidingWindowService(slidingWindowConfig, llmProvider, model);
        }

        // Try to apply async summary result if ready
        if (shortTermMemory != null) {
            shortTermMemory.tryApplyAsyncResult();
        }

        if (slidingWindow.shouldSlide(getMessages())) {
            var beforeSize = getMessages().size();
            summarizeEvictedMessages();

            // Execute sliding window
            var slidMessages = slidingWindow.slide(getMessages());
            clearMessages();
            addMessages(slidMessages);
            logger.info("Sliding window applied: {} -> {} messages", beforeSize, getMessages().size());
        }
    }

    private void summarizeEvictedMessages() {
        if (shortTermMemory == null) return;
        var evicted = slidingWindow.getEvictedMessages(getMessages());
        if (evicted.isEmpty()) return;
        shortTermMemory.summarize(evicted);
        logger.info("Summarized {} evicted messages", evicted.size());
    }

    // Public accessors for reflection executor
    public Double getTemperature() {
        return temperature;
    }

    public String getModel() {
        return model;
    }

    public LLMProvider getLLMProvider() {
        return llmProvider;
    }

    public List<Message> turn(List<Message> messages, List<Tool> tools, String model) {
        var resultMsg = new ArrayList<Message>();
        var choice = handLLM(messages, tools, model);
        resultMsg.add(choice.message);
        if (choice.finishReason == FinishReason.TOOL_CALLS) {
            if (!Strings.isBlank(choice.message.content)) {
                logger.info(choice.message.content);
            }
            var funcMsg = handleFunc(choice.message);
            resultMsg.addAll(funcMsg);
        }
        return resultMsg;
    }

    private boolean lastIsToolMsg() {
        return RoleType.TOOL == getMessages().getLast().role;
    }

    private Choice handLLM(List<Message> messages, List<Tool> tools, String model) {
        var req = CompletionRequest.of(messages, tools, llmProvider.config == null ? 0 : llmProvider.config.getTemperature(), model, this.getName());
        return aroundLLM(r -> llmProvider.completionStream(r, elseDefaultCallback()), req);
    }

    private Choice aroundLLM(Function<CompletionRequest, CompletionResponse> func, CompletionRequest request) {
        agentLifecycles.forEach(alc -> alc.beforeModel(request, getExecutionContext()));

        var resp = func.apply(request);
        addTokenCost(resp.usage);

        agentLifecycles.forEach(alc -> alc.afterModel(request, resp, getExecutionContext()));
        return resp.choices.getFirst();
    }

    private StreamingCallback elseDefaultCallback() {
        return getStreamingCallback() == null ? new DefaultStreamingCallback() : getStreamingCallback();
    }

    public List<Message> handleFunc(Message funcMsg) {
        return funcMsg.toolCalls.stream()
                .map(tool -> {
                    var callResult = getToolExecutor().execute(tool, getExecutionContext());
                    return Map.entry(tool, callResult);
                }).map(entry -> {
                    var tool = entry.getKey();
                    var callResult = entry.getValue();
                    return Message.of(RoleType.TOOL, callResult, tool.function.name, tool.id, null, null);
                }).toList();
    }

    private ToolExecutor getToolExecutor() {
        if (toolExecutor == null) {
            toolExecutor = new ToolExecutor(toolCalls, agentLifecycles, getActiveTracer(), this::updateNodeStatus);
        }
        toolExecutor.setAuthenticated(authenticated);
        return toolExecutor;
    }

    private void buildUserQueryToMessage(String query, Map<String, Object> variables) {
        if (getMessages().isEmpty()) {
            addMessage(buildSystemMessage(variables));
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

        // Inject short-term memory summary
        if (shortTermMemory != null) {
            prompt += shortTermMemory.buildSummaryBlock();
        }

        return Message.of(RoleType.SYSTEM, prompt, getName());
    }

    private void rag(String query, Map<String, Object> variables) {
        if (ragConfig.vectorStore() == null || ragConfig.llmProvider() == null)
            throw new RuntimeException("vectorStore/llmProvider cannot be null if useRag flag is enabled");

        // Step 1: Query rewriting for better retrieval (optional based on configuration)
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
        return this.toolCalls;
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
}