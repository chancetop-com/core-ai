package ai.core.agent;

import ai.core.agent.formatter.Formatter;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.agent.listener.MessageUpdatedEventListener;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.document.Tokenizer;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.persistence.Persistence;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.SystemVariables;
import ai.core.rag.LongQueryHandler;
import ai.core.telemetry.Tracer;
import ai.core.termination.Termination;
import core.framework.util.Lists;
import core.framework.util.Maps;
import core.framework.util.Strings;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public abstract class Node<T extends Node<T>> {
    private static final String NAME_REGEX_PATTERN = "^[^\\s<|\\\\/>]+$";
    private String id;
    private String name;
    private String description;
    private NodeType nodeType;
    private MessageUpdatedEventListener<T> messageUpdatedEventListener;
    private NodeStatus nodeStatus;
    private Persistence<T> persistence;
    private PersistenceProvider persistenceProvider;
    private LongQueryHandler longQueryHandler;
    private String input;
    private String output;
    private String rawOutput;
    private Integer round;
    private Integer maxRound;
    private Node<?> parent;
    private Node<?> next;
    private Boolean streaming;
    private StreamingCallback streamingCallback;
    private Tracer tracer;
    private ExecutionContext executionContext;
    private final List<Termination> terminations;
    private final List<Message> messages;
    private final Usage currentTokenUsage = new Usage();
    private final Map<String, Object> systemVariables = Maps.newHashMap();
    private final Pattern compiledPattern = Pattern.compile(NAME_REGEX_PATTERN);
    List<AbstractLifecycle> agentLifecycles = Lists.newArrayList();

    public Node() {
        this.nodeStatus = NodeStatus.INITED;
        this.messages = Lists.newArrayList();
        this.terminations = Lists.newArrayList();
    }

    public void setMessageUpdatedEventListener(MessageUpdatedEventListener<T> listener) {
        messageUpdatedEventListener = listener;
    }

    public void updateNodeStatus(NodeStatus nodeStatus) {
        this.nodeStatus = nodeStatus;
    }

    public boolean notTerminated() {
        return terminations.isEmpty() || terminations.stream().noneMatch(v -> v.terminate(this));
    }

    void setNodeStatus(NodeStatus nodeStatus) {
        this.nodeStatus = nodeStatus;
    }

    public Optional<MessageUpdatedEventListener<T>> getMessageUpdatedEventListener() {
        return Optional.ofNullable(messageUpdatedEventListener);
    }

    public boolean isStreaming() {
        return streaming != null && streaming;
    }

    public StreamingCallback getStreamingCallback() {
        return streamingCallback;
    }

    public String getId() {
        return this.id;
    }

    public String getInput() {
        return this.input;
    }

    public String getOutput() {
        return this.output;
    }

    public String getRawOutput() {
        return this.rawOutput;
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public NodeType getType() {
        return this.nodeType;
    }

    public Integer getRound() {
        return this.round;
    }

    public Integer getMaxRound() {
        return this.maxRound;
    }

    public Usage getCurrentTokenUsage() {
        return this.currentTokenUsage;
    }

    public Node<?> getParentNode() {
        return this.parent;
    }

    public NodeStatus getNodeStatus() {
        return this.nodeStatus;
    }

    public List<Message> getMessages() {
        return this.messages;
    }

    public LongQueryHandler getLongQueryHandler() {
        return this.longQueryHandler;
    }

    public Node<?> getNext() {
        return next;
    }

    public void setRawOutput(String output) {
        this.rawOutput = output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public void putSystemVariable(Map<String, Object> variables) {
        this.systemVariables.putAll(variables);
    }

    public void putSystemVariable(String key, Object value) {
        this.systemVariables.put(key, value);
    }

    void setInput(String input) {
        this.input = input;
    }

    public void reset() {
        this.clearMessages();
        this.setInput(null);
        this.setOutput(null);
        this.setRawOutput(null);
        this.setRound(0);
        this.nodeStatus = NodeStatus.INITED;
    }

    public void setNext(Node<?> node) {
        this.next = node;
    }

    public boolean hasPersistenceProvider() {
        return persistenceProvider != null;
    }

    @SuppressWarnings("unchecked")
    public void load(String id) {
        if (persistenceProvider == null) throw new RuntimeException("PersistenceProvider is not set");
        persistenceProvider.load(id).ifPresent(data -> {
            persistence.deserialization((T) this, data);
            this.id = id;
        });
    }

    @SuppressWarnings("unchecked")
    public String save(String id) {
        if (persistenceProvider == null) throw new RuntimeException("PersistenceProvider is not set");
        persistenceProvider.save(id, persistence.serialization((T) this));
        return id;
    }

    private String aroundExecute(BiFunction<String, Map<String, Object>, String> exec, String query) {
        try {
            AtomicReference<String> queryRef = new AtomicReference<>(query);
            agentLifecycles.forEach(alc -> alc.beforeAgentRun(queryRef, getExecutionContext()));
            var rs = exec.apply(queryRef.get(), getExecutionContext().getCustomVariables());
            AtomicReference<String> resultRef = new AtomicReference<>(rs);
            agentLifecycles.forEach(alc -> alc.afterAgentRun(queryRef.get(), resultRef, getExecutionContext()));
            return resultRef.get();
        } catch (Exception e) {
            agentLifecycles.forEach(alc -> alc.afterAgentFailed(query, getExecutionContext(), e));
            throw e;
        }
    }

    public final String run(String query, ExecutionContext context) {
        this.executionContext = context;
        return run(query);
    }

    public final String run(String query, Map<String, Object> variables) {
        return run(query, ExecutionContext.builder().customVariables(variables).build());
    }
    public final String run(String query) {
        try {
            return aroundExecute((q, v) -> {
                setupNodeSystemVariables(q);
                return execute(q, v);
            }, query);
        } catch (ai.core.session.ToolCallDeniedException e) {
            throw e;
        } catch (Exception e) {
            updateNodeStatus(NodeStatus.FAILED);
            throw new RuntimeException(Strings.format("Run node {}<{}> failed: {}, raw request/response: {}, {}",
                this.name, this.id, e.getMessage(), getInput(), getFailedMessage()), e);
        }
    }
    private String getFailedMessage() {
        var content = getRawOutput() == null ? getOutput() : getRawOutput();
        var lastToolCalls = getMessages().getLast().toolCalls;
        if (Strings.isBlank(content) && lastToolCalls != null && !lastToolCalls.isEmpty()) content = lastToolCalls.getLast().function.name;
        return content;
    }

    private void setupNodeSystemVariables(String query) {
        if (this.round != null) systemVariables.put(SystemVariables.NODE_CURRENT_ROUND, this.round);
        systemVariables.put(SystemVariables.NODE_CURRENT_INPUT, query);
        systemVariables.put(SystemVariables.SYSTEM_CURRENT_TIME, ZonedDateTime.now());
    }

    abstract String execute(String query, Map<String, Object> variables);

    abstract void setChildrenParentNode();

    void setName(String name) {
        if (name == null || !compiledPattern.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid name: " + name + ", it must match the pattern: " + NAME_REGEX_PATTERN);
        }
        this.name = name;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setNodeType(NodeType type) {
        this.nodeType = type;
    }

    void setStreaming(Boolean streaming) {
        this.streaming = streaming;
    }

    public void setStreamingCallback(StreamingCallback streamingCallback) {
        this.streamingCallback = streamingCallback;
    }

    public void addLifecycle(AbstractLifecycle lifecycle) {
        this.agentLifecycles.add(lifecycle);
    }

    void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @SuppressWarnings("unchecked")
    <R extends Tracer> R getTracer() {
        return (R) tracer;
    }

    public void setExecutionContext(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    public ExecutionContext getExecutionContext() {
        if (executionContext != null) return executionContext;
        var builder = ExecutionContext.builder();
        if (persistenceProvider != null) {
            builder.persistenceProvider(persistenceProvider);
        }
        executionContext = builder.build();
        return executionContext;
    }

    public void setRound(Integer round) {
        this.round = round;
    }

    void setMaxRound(Integer maxRound) {
        this.maxRound = maxRound;
    }

    void setLongQueryHandler(LongQueryHandler longQueryHandler) {
        this.longQueryHandler = longQueryHandler;
    }

    void setFormatter(Formatter formatter) {
    }
    void setPersistence(Persistence<T> persistence) {
        this.persistence = persistence;
    }
    void setPersistenceProvider(PersistenceProvider persistenceProvider) {
        this.persistenceProvider = persistenceProvider;
    }
    void addMessage(Message message) {
        if (message.role == RoleType.ASSISTANT || message.role == RoleType.TOOL) {
            addAssistantOrToolMessage(message);
        } else {
            this.messages.add(message);
        }
    }
    void addMessages(List<Message> messages) {
        this.messages.addAll(messages);
    }
    void removeMessage(Message message) {
        this.messages.remove(message);
    }
    void clearMessages() {
        this.messages.clear();
    }
    Map<String, Object> getSystemVariables() {
        return this.systemVariables;
    }
    public void addTokenCost(Usage cost) {
        if (cost == null) return;
        this.currentTokenUsage.add(cost);
        if (this.parent != null) this.parent.addTokenCost(cost);
        var ctx = this.executionContext;
        if (ctx != null && ctx.getTokenCostCallback() != null) ctx.getTokenCostCallback().accept(cost);
    }

    public void setParentNode(Node<?> parent) {
        this.parent = parent;
    }

    void addTermination(Termination termination) {
        if (termination == null) return;
        this.terminations.add(termination);
    }

    void setId(String id) {
        this.id = id;
    }

    boolean currentTokenUsageOutOfMax(String query, Integer max) {
        return Tokenizer.tokenCount(query) + getCurrentTokenUsage().getTotalTokens() > max * 0.8;
    }

    String handleToShortQuery(String currentQuery, String question) {
        if (longQueryHandler == null) return "The result text is too long for LLM, please re-planning to ensure the task can proceed.";
        var rst = longQueryHandler.handler(question, currentQuery);
        addTokenCost(rst.usage());
        return rst.shorterQuery();
    }

    void addTerminations(List<Termination> terminations) {
        if (terminations == null || terminations.isEmpty()) return;
        this.terminations.addAll(terminations);
    }

    @SuppressWarnings("unchecked")
    void addAssistantOrToolMessage(Message message) {
        this.messages.add(message);
        this.getMessageUpdatedEventListener().ifPresent(v -> v.eventHandler((T) this, message));
        if (this.parent != null) this.parent.addAssistantOrToolMessage(message);
    }
}
