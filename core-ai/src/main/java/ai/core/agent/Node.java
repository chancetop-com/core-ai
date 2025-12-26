package ai.core.agent;

import ai.core.agent.formatter.Formatter;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.agent.listener.ChainNodeStatusChangedEventListener;
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
import ai.core.task.Task;
import ai.core.task.TaskArtifact;
import ai.core.task.TaskMessage;
import ai.core.task.TaskRoleType;
import ai.core.task.TaskStatus;
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
    private Task task;
    private Boolean streaming;
    private StreamingCallback streamingCallback;
    private Tracer tracer;
    private ExecutionContext executionContext;
    private final List<Termination> terminations;
    private final Map<NodeStatus, ChainNodeStatusChangedEventListener<T>> statusChangedEventListeners;
    private final List<Message> messages;
    private final Usage currentTokenUsage = new Usage();
    private final Map<String, Object> systemVariables = Maps.newHashMap();
    private final Pattern compiledPattern = Pattern.compile(NAME_REGEX_PATTERN);
    List<AbstractLifecycle> agentLifecycles = Lists.newArrayList();

    public Node() {
        this.nodeStatus = NodeStatus.INITED;
        this.messages = Lists.newArrayList();
        this.terminations = Lists.newArrayList();
        this.statusChangedEventListeners = Maps.newConcurrentHashMap();
    }

    public void addStatusChangedEventListener(NodeStatus status, ChainNodeStatusChangedEventListener<T> eventListener) {
        if (eventListener == null) return;
        statusChangedEventListeners.put(status, eventListener);
    }

    public void addStatusChangedEventListeners(Map<NodeStatus, ChainNodeStatusChangedEventListener<T>> listeners) {
        if (listeners == null || listeners.isEmpty()) return;
        statusChangedEventListeners.putAll(listeners);
    }

    public void setMessageUpdatedEventListener(MessageUpdatedEventListener<T> listener) {
        messageUpdatedEventListener = listener;
    }

    @SuppressWarnings("unchecked")
    public void updateNodeStatus(NodeStatus nodeStatus) {
        this.nodeStatus = nodeStatus;
        getStatusChangedEventListener(nodeStatus).ifPresent(listener -> listener.eventHandler((T) this));
    }

    public boolean notTerminated() {
        return terminations.isEmpty() || terminations.stream().noneMatch(v -> v.terminate(this));
    }

    void setNodeStatus(NodeStatus nodeStatus) {
        this.nodeStatus = nodeStatus;
    }

    public Optional<ChainNodeStatusChangedEventListener<T>> getStatusChangedEventListener(NodeStatus status) {
        return Optional.ofNullable(statusChangedEventListeners.get(status));
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

    public Task getTask() {
        return this.task;
    }

    void setInput(String input) {
        this.input = input;
    }

    public void clearShortTermMemory() {
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

    @SuppressWarnings("unchecked")
    public void load(String id) {
        if (persistenceProvider == null) {
            throw new RuntimeException("PersistenceProvider is not set");
        }
        if (persistenceProvider.load(id).isPresent()) {
            persistence.deserialization((T) this, persistenceProvider.load(id).get());
            this.id = id;
        }
    }

    @SuppressWarnings("unchecked")
    public String save(String id) {
        if (persistenceProvider == null) {
            throw new RuntimeException("PersistenceProvider is not set");
        }
        persistenceProvider.save(id, persistence.serialization((T) this));
        return id;
    }


    private String aroundExecute(BiFunction<String, Map<String, Object>, String> exec, String query) {
        try {
            AtomicReference<String> queryRef = new AtomicReference<>(query);
            agentLifecycles.forEach(alc -> alc.beforeAgentRun(queryRef, getExecutionContext()));
            // execute raw method
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
        // Compatible with legacy method calls
        return run(query, ExecutionContext.builder().customVariables(variables).build());
    }

    public final String run(String query) {
        try {
            return aroundExecute((q, v) -> {
                setupNodeSystemVariables(q);
                return execute(q, v);
            }, query);
        } catch (Exception e) {
            updateNodeStatus(NodeStatus.FAILED);
            throw new RuntimeException(
                    Strings.format("Run node {}<{}> failed: {}, raw request/response: {}, {}",
                            this.name,
                            this.id,
                            e.getMessage(),
                            getInput(),
                            getFailedMessage()),
                    e);
        }
    }

    public final void run(Task task) {
        if (task == null) throw new IllegalArgumentException("Task cannot be null");
        if (this.task == null) this.task = task;
        var lastMessage = task.getLastMessage();
        // need user input but new query not yet submitted, return and wait
        if (task.getStatus() == TaskStatus.INPUT_REQUIRED && lastMessage.getRole() != TaskRoleType.USER) {
            throw new IllegalArgumentException("Task is waiting for user input, please submit the query first");
        }
        task.setStatus(TaskStatus.WORKING);
        var rst = run(lastMessage.getTextPart().getText());
        task.addHistories(List.of(TaskMessage.of(TaskRoleType.AGENT, rst)));
        task.addArtifacts(List.of(TaskArtifact.of(this.getName(), null, null, rst, true, true)));
        if (nodeStatus == NodeStatus.WAITING_FOR_USER_INPUT) {
            task.setStatus(TaskStatus.INPUT_REQUIRED);
        } else {
            task.setStatus(TaskStatus.COMPLETED);
        }
    }

    public final void run(Task task, ExecutionContext context) {
        this.executionContext = context;
        run(task);
    }

    private String getFailedMessage() {
        var content = getRawOutput() == null ? getOutput() : getRawOutput();
        if (Strings.isBlank(content) && getMessages().getLast().functionCall != null && getMessages().getLast().functionCall.function != null) {
            content = getMessages().getLast().functionCall.function.name;
        }
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
        if (!validateString(name)) {
            throw new IllegalArgumentException("Invalid name: " + name + ", it must match the pattern: " + NAME_REGEX_PATTERN);
        }
        this.name = name;
    }

    private boolean validateString(String input) {
        if (input == null) {
            return false;
        }
        var matcher = compiledPattern.matcher(input);
        return matcher.matches();
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

    void setStreamingCallback(StreamingCallback streamingCallback) {
        this.streamingCallback = streamingCallback;
    }

    void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @SuppressWarnings("unchecked")
    <R extends Tracer> R getTracer() {
        return (R) tracer;
    }

    void setExecutionContext(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    public ExecutionContext getExecutionContext() {
        return executionContext != null ? executionContext : ExecutionContext.empty();
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

    void addTaskHistoriesToMessages() {
        if (this.task == null) return;
        var histories = this.task.getHistory();
        if (histories.size() <= 1) return;
        var subHistories = histories.subList(0, histories.size() - 1);
        addMessages(subHistories.stream().map(this::toLLMMessage).toList());
    }

    private Message toLLMMessage(TaskMessage message) {
        return Message.of(toRoleType(message.getRole()), message.getTextPart().getText());
    }

    private RoleType toRoleType(TaskRoleType role) {
        return switch (role) {
            case USER -> RoleType.USER;
            case AGENT -> RoleType.ASSISTANT;
        };
    }

    void clearMessages() {
        this.messages.clear();
    }

    Map<String, Object> getSystemVariables() {
        return this.systemVariables;
    }

    public void addTokenCost(Usage cost) {
        if (cost == null) return;
        this.currentTokenUsage.setCompletionTokens(this.currentTokenUsage.getCompletionTokens() + cost.getCompletionTokens());
        this.currentTokenUsage.setPromptTokens(this.currentTokenUsage.getPromptTokens() + cost.getPromptTokens());
        this.currentTokenUsage.setTotalTokens(this.currentTokenUsage.getTotalTokens() + cost.getTotalTokens());
        if (this.parent != null) {
            this.parent.addTokenCost(cost);
        }
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
        if (getLongQueryHandler() == null) {
            return "The result text is too long for LLM, please re-planning to ensure the task can proceed.";
        }
        var rst = getLongQueryHandler().handler(question, currentQuery);
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
        if (this.parent != null) {
            this.parent.addAssistantOrToolMessage(message);
        }
    }
}
