package ai.core.agent;

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
import ai.core.memory.memories.NaiveMemory;
import ai.core.prompt.Prompts;
import ai.core.prompt.engines.MustachePromptTemplate;
import ai.core.rag.RagConfig;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionExecutor;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.context.AgentTraceContext;
import ai.core.tool.ToolCall;
import core.framework.crypto.Hash;
import core.framework.json.JSON;
import core.framework.util.Maps;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
    NaiveMemory longTernMemory;
    Boolean useGroupContext;
    Integer maxToolCallCount;
    //    Integer currentToolCallCount;
    Boolean authenticated = false;

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

        // todo context exceed

        // set authenticated flag if the agent is authenticated
        if (getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT && Prompts.CONFIRMATION_PROMPT.equalsIgnoreCase(query)) {
            authenticated = true;
        }

        // update chain node status
        updateNodeStatus(NodeStatus.RUNNING);

        // chat with LLM the first time
        chatCore(prompt, variables);

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

    // Public method accessible to ReflectionExecutor for regenerating solutions
    public void chatCore(String query, Map<String, Object> variables) {
        buildUserQueryToMessage(query, variables);
        var currentIteCount = 0;
        var agentOut = new StringBuilder();
        do {
            // turn = call model + call func
            var turnMsgList = turn(getMessages(), toReqTools(toolCalls), model);
            // add msg into global
            turnMsgList.forEach(this::addMessage);
            // include agent loop msg ,but not tool msg
            agentOut.append(turnMsgList.stream().filter(m -> RoleType.ASSISTANT.equals(m.role)).map(m -> m.content).reduce((s1, s2) -> s1 + s2));
            currentIteCount++;
        } while (lastIsToolMsg() && currentIteCount < maxToolCallCount);
        // set out
        setOutput(agentOut.toString());
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

        agentLifecycles.forEach(alc -> alc.afterModel(resp, getExecutionContext()));
        return resp.choices.getFirst();
    }

    private StreamingCallback elseDefaultCallback() {
        if (getStreamingCallback() == null) {
            return new StreamingCallback() {
                @Override
                public void onChunk(String chunk) {
                }

                @Override
                public void onComplete() {
                }

                @Override
                public void onError(Throwable error) {
                }
            };
        } else {
            return getStreamingCallback();
        }
    }

    public List<Message> handleFunc(Message funcMsg) {
        return funcMsg.toolCalls.stream()
                .map(tool -> {
                    var callResult = aroundTool(tool, getExecutionContext());
                    return Map.entry(tool, callResult);
                }).map(entry -> {
                    var tool = entry.getKey();
                    var callResult = entry.getValue();
                    return Message.of(RoleType.TOOL, callResult, tool.function.name, tool.id, null, null);
                }).toList();
    }

    /**
     * Execute reflection process using ReflectionExecutor.
     * Delegates all reflection logic to the dedicated executor.
     */
    private void reflection(Map<String, Object> variables) {
        ReflectionExecutor executor = new ReflectionExecutor(this, reflectionConfig, variables);
        executor.execute();
    }


    private void buildUserQueryToMessage(String query, Map<String, Object> variables) {
        if (getMessages().isEmpty()) {
            addMessage(buildSystemMessageWithLongTernMemory(query, variables));
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


    private Message buildSystemMessageWithLongTernMemory(String query, Map<String, Object> variables) {
        var prompt = systemPrompt;
        if (getParentNode() != null && isUseGroupContext()) {
            this.putSystemVariable(getParentNode().getSystemVariables());
        }
        Map<String, Object> var = Maps.newConcurrentHashMap();
        if (variables != null) var.putAll(variables);
        var.putAll(getSystemVariables());
        prompt = new MustachePromptTemplate().execute(prompt, var, Hash.md5Hex(promptTemplate));
        if (!getLongTernMemory().retrieve(query).isEmpty()) {
            prompt += NaiveMemory.PROMPT_MEMORY_TEMPLATE + getLongTernMemory().toString();
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

            // Trace tool call
            var activeTracer = getActiveTracer();
            if (activeTracer != null) {
                return activeTracer.traceToolCall(
                        toolCall.function.name,
                        toolCall.function.arguments,
                        () -> function.call(toolCall.function.arguments)
                );
            }
            return function.call(toolCall.function.arguments);
        } catch (Exception e) {
            throw new BadRequestException(Strings.format("tool call failed<execute>:\n{}, cause:\n{}", JSON.toJSON(toolCall), e.getMessage()), "TOOL_CALL_FAILED", e);
        }
    }

    private String aroundTool(FunctionCall functionCall, ExecutionContext executionContext) {
        // before
        agentLifecycles.forEach(alc -> alc.beforeTool(functionCall, executionContext));
        // raw call
        var funcResult = functionCall(functionCall);
        // after
        AtomicReference<String> resultRef = new AtomicReference<>(funcResult);
        agentLifecycles.forEach(alc -> alc.afterTool(resultRef, executionContext));
        return resultRef.get();
    }


    private void rag(String query, Map<String, Object> variables) {
        if (ragConfig.vectorStore() == null || ragConfig.llmProvider() == null)
            throw new RuntimeException("vectorStore/llmProvider cannot be null if useRag flag is enabled");
        var ragQuery = query;
        if (ragConfig.llmProvider() != null) {
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

    public ReflectionConfig getReflectionConfig() {
        return this.reflectionConfig;
    }

    public List<ToolCall> getToolCalls() {
        return this.toolCalls;
    }

    public NaiveMemory getLongTernMemory() {
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

    public Message getLastToolCallMessage() {
        for (var msg : getMessages().reversed()) {
            if (msg.role == RoleType.TOOL) {
                return msg;
            }
        }
        return null;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
}

