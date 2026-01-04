package ai.core.agent;

import ai.core.agent.slashcommand.SlashCommandParser;
import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.agent.streaming.DefaultStreamingCallback;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.defaultagents.DefaultRagQueryRewriteAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.memory.ShortTermMemory;
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
import java.util.UUID;
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
    private AgentMemoryCoordinator memoryCoordinator;

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

    private AgentTracer getActiveTracer() {
        return getTracer();
    }

    private void chatCommand(String query, Map<String, Object> variables) {
        chatTurns(query, variables, this::constructionFakeSlashCommandAssistantMsg);
    }

    private void chatLoops(String query, Map<String, Object> variables, boolean skipReflection) {
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
        chatTurns(prompt, variables, this::handLLM);

        // Reflection loop if enabled and not skipped
        if (reflectionConfig != null && reflectionConfig.enabled() && !skipReflection) {
            reflectionLoop(variables);
        }
    }
    private String doExecute(String query, Map<String, Object> variables) {
        return doExecute(query, variables, false);
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
        // Update status to completed only for first execution
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


    private String generateToolCallId() {
        return "slash_command_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // Execute reflection loop to iteratively improve the solution
    private void reflectionLoop(Map<String, Object> variables) {
        ReflectionHistory history = new ReflectionHistory(getId(), getName(), getInput(), reflectionConfig.evaluationCriteria());
        int currentRound = 1;
        setRound(currentRound);  // Initialize round counter
        if (reflectionListener != null)
            reflectionListener.onReflectionStart(this, getInput(), reflectionConfig.evaluationCriteria());

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

    private boolean isValidEvaluation(ReflectionEvaluation evaluation) {
        return evaluation.getScore() >= 1 && evaluation.getScore() <= 10;
    }

    private boolean shouldTerminateReflection(ReflectionEvaluation eval, int round) {
        if (eval.isPass() && eval.getScore() >= 8) return true;
        if (!eval.isShouldContinue()) return true;
        return round >= reflectionConfig.minRound() && eval.getScore() >= 8;
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

    // Public method for chat execution (used by executeAgentFlow)
    public void chatTurns(String query, Map<String, Object> variables, BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
        buildUserQueryToMessage(query, variables);
        var currentIteCount = 0;
        var agentOut = new StringBuilder();
        do {
            // apply sliding window if needed before each LLM call
            applyMemoryCoordinatorIfNeeded();
            // turn = call model + call func
            var turnMsgList = turn(getMessages(), toReqTools(toolCalls), constructionAssistantMsg);
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


    private Choice constructionFakeSlashCommandAssistantMsg(List<Message> messages, List<Tool> tools) {
        logger.debug("all tools size is {}", tools.size());
        String query = messages.getLast().content;
        var command = SlashCommandParser.parse(query);
        if (command.isNotValid()) {
            return Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "Error: Invalid slash command format. Expected: /slash_command:tool_name:arguments"));
        } else {
            var functionCall = FunctionCall.of(generateToolCallId(), "function", command.getToolName(), command.hasArguments() ? command.getArguments() : "{}");
            return Choice.of(FinishReason.TOOL_CALLS, Message.of(RoleType.ASSISTANT, "", "assistant", null, null, List.of(functionCall)));
        }
    }

    private void applyMemoryCoordinatorIfNeeded() {
        if (slidingWindowConfig == null) {
            return;
        }
        if (memoryCoordinator == null) {
            memoryCoordinator = new AgentMemoryCoordinator(slidingWindowConfig, llmProvider, model, shortTermMemory);
        }
        memoryCoordinator.applySlidingWindowIfNeeded(this::getMessages, msgs -> {
            clearMessages();
            addMessages(msgs);
        });
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

    public List<Message> turn(List<Message> messages, List<Tool> tools, BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
        var resultMsg = new ArrayList<Message>();
        var choice = constructionAssistantMsg.apply(messages, tools);
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

    private Choice handLLM(List<Message> messages, List<Tool> tools) {
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
        return funcMsg.toolCalls.parallelStream().map(tool -> {
            var msg = new ArrayList<Message>();
            var result = getToolExecutor().execute(tool, getExecutionContext());
            if (result.isDirectReturn() || Strings.isBlank(result.toResultForLLM())) {
                msg.add(Message.of(RoleType.TOOL, specialReminder(tool.function.name, result.toResultForLLM()), tool.function.name, tool.id, null, null));
                msg.add(Message.of(RoleType.ASSISTANT, result.toResultForLLM()));
            } else {
                msg.add(Message.of(RoleType.TOOL, result.toResultForLLM(), tool.function.name, tool.id, null, null));
            }
            return msg;
        }).flatMap(List::stream).toList();
    }

    private String specialReminder(String toolName, String toolResult) {
        return Prompts.TOOL_DIRECT_RETURN_REMINDER_PROMPT.formatted(toolName, toolResult);
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
            addTaskHistoriesToMessages();
        }

        if (isUseGroupContext() && getParentNode() != null) {
            addMessages(getParentNode().getMessages());
        }

        var reqMsg = Message.of(RoleType.USER, query, buildRequestName(false), null, null, null);
        removeLastAssistantToolCallMessageIfNotToolResult(reqMsg);
        addMessage(reqMsg);
    }

    private List<Tool> toReqTools(List<ToolCall> toolCalls) {
        return toolCalls.stream().filter(ToolCall::isLlmVisible).map(ToolCall::toTool).toList();
    }

    private void removeLastAssistantToolCallMessageIfNotToolResult(Message reqMsg) {
        var lastMsg = getMessages().getLast();
        if (lastMsg.role == RoleType.ASSISTANT && lastMsg.toolCalls != null
                && !lastMsg.toolCalls.isEmpty() && reqMsg.role != RoleType.TOOL) {
            removeMessage(lastMsg);
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
        return Message.of(RoleType.SYSTEM, prompt, getName());
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