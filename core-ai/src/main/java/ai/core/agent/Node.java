package ai.core.agent;

import ai.core.agent.formatter.Formatter;
import ai.core.agent.listener.ChainNodeStatusChangedEventListener;
import ai.core.agent.listener.MessageUpdatedEventListener;
import ai.core.document.Tokenizer;
import ai.core.llm.providers.inner.CompletionResponse;
import ai.core.llm.providers.inner.LLMMessage;
import ai.core.llm.providers.inner.Usage;
import ai.core.persistence.Persistence;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.SystemVariables;
import ai.core.rag.LongQueryHandler;
import ai.core.task.Task;
import ai.core.task.TaskArtifact;
import ai.core.task.TaskMessage;
import ai.core.task.TaskRoleType;
import ai.core.task.TaskStatus;
import ai.core.termination.Termination;
import core.framework.util.Lists;
import core.framework.util.Maps;
import core.framework.util.Strings;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * @author stephen
 */
public abstract class Node<T extends Node<T>> {
    private final String id;
    private String name;
    private String description;
    private NodeType nodeType;
    private Formatter formatter;
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
    private final List<Termination> terminations;
    private final Map<NodeStatus, ChainNodeStatusChangedEventListener<T>> statusChangedEventListeners;
    private final List<LLMMessage> messages;
    private final Usage currentTokenUsage = new Usage();
    private final Map<String, Object> systemVariables = Maps.newHashMap();

    public Node() {
        this.id = UUID.randomUUID().toString();
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

    boolean notTerminated() {
        return terminations.isEmpty() || terminations.stream().noneMatch(v -> v.terminate(this));
    }

    void formatContent(CompletionResponse rst, Formatter formatter) {
        var message = rst.choices.getFirst().message;
        message.content = formatter.formatter(message.content);
    }

    public Optional<ChainNodeStatusChangedEventListener<T>> getStatusChangedEventListener(NodeStatus status) {
        return Optional.ofNullable(statusChangedEventListeners.get(status));
    }

    public Optional<MessageUpdatedEventListener<T>> getMessageUpdatedEventListener() {
        return Optional.ofNullable(messageUpdatedEventListener);
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

    public List<Termination> getTerminations() {
        return this.terminations;
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

    public List<LLMMessage> getMessages() {
        return this.messages;
    }

    public Formatter getAgentFormatter() {
        return this.formatter;
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
            return;
        }
        throw new NoSuchElementException("No data found");
    }

    @SuppressWarnings("unchecked")
    public String save() {
        if (persistenceProvider == null) {
            throw new RuntimeException("PersistenceProvider is not set");
        }
        persistenceProvider.save(this.id, persistence.serialization((T) this));
        return this.id;
    }

    // The variables are used by the whole node, for example, the variables can be used by the agent, chain or group and their children if exists
    public final String run(String query, Map<String, Object> variables) {
        try {
            setupNodeSystemVariables(query);
            return execute(query, variables);
        } catch (Exception e) {
            updateNodeStatus(NodeStatus.FAILED);
            throw new RuntimeException(Strings.format("Run node {}<{}> failed: {}, raw request/response: {}, {}", this.name, this.id, e.getMessage(), getInput(), getRawOutput()), e);
        }
    }

    public final void run(Task task, Map<String, Object> variables) {
        if (task == null) throw new IllegalArgumentException("Task cannot be null");
        if (this.task == null) this.task = task;
        var lastMessage = task.getLastMessage();
        // need user input but new query not yet submitted, return and wait
        if (task.getStatus() == TaskStatus.INPUT_REQUIRED && lastMessage.getRole() != TaskRoleType.USER) {
            throw new IllegalArgumentException("Task is waiting for user input, please submit the query first");
        }
        // task is completed, failed, canceled or unknown
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED || task.getStatus() == TaskStatus.CANCELED || task.getStatus() == TaskStatus.UNKNOWN) {
            throw new IllegalArgumentException("Task is already completed, failed, canceled or unknown");
        }
        task.setStatus(TaskStatus.WORKING);
        try {
            var rst = run(lastMessage.getTextPart().getText(), variables);
            task.addHistories(List.of(TaskMessage.of(TaskRoleType.AGENT, rst)));
            task.addArtifacts(List.of(TaskArtifact.of(this.getName(), null, null, rst, true, true)));
            if (nodeStatus == NodeStatus.WAITING_FOR_USER_INPUT) {
                task.setStatus(TaskStatus.INPUT_REQUIRED);
            } else {
                task.setStatus(TaskStatus.COMPLETED);
            }
        } catch (Exception e) {
            task.addHistories(List.of(TaskMessage.of(TaskRoleType.AGENT, e.getMessage())));
            task.setStatus(TaskStatus.FAILED);
        }
    }

    private void setupNodeSystemVariables(String query) {
        systemVariables.put(SystemVariables.NODE_CURRENT_ROUND, this.round);
        systemVariables.put(SystemVariables.NODE_CURRENT_INPUT, query);
    }

    abstract String execute(String query, Map<String, Object> variables);

    abstract void setChildrenParentNode();

    void setName(String name) {
        this.name = name;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setNodeType(NodeType type) {
        this.nodeType = type;
    }

    void setRound(Integer round) {
        this.round = round;
    }

    void setMaxRound(Integer maxRound) {
        this.maxRound = maxRound;
    }

    void setLongQueryHandler(LongQueryHandler longQueryHandler) {
        this.longQueryHandler = longQueryHandler;
    }

    void setFormatter(Formatter formatter) {
        this.formatter = formatter;
    }

    void setPersistence(Persistence<T> persistence) {
        this.persistence = persistence;
    }

    void setPersistenceProvider(PersistenceProvider persistenceProvider) {
        this.persistenceProvider = persistenceProvider;
    }

    void addMessage(LLMMessage message) {
        this.messages.add(message);
    }

    void addMessages(List<LLMMessage> messages) {
        this.messages.addAll(messages);
    }

    void addTaskHistoriesToMessages() {
        if (this.task == null) return;
        var histories = this.task.getHistory();
        addMessages(histories.stream().map(this::toLLMMessage).toList());
    }

    private LLMMessage toLLMMessage(TaskMessage message) {
        return LLMMessage.of(toLLMRole(message.getRole()), message.getTextPart().getText());
    }

    private AgentRole toLLMRole(TaskRoleType role) {
        return switch (role) {
            case USER -> AgentRole.USER;
            case AGENT -> AgentRole.ASSISTANT;
            default -> throw new IllegalArgumentException("Unsupported role: " + role);
        };
    }

    void clearMessages() {
        this.messages.clear();
    }

    Map<String, Object> getSystemVariables() {
        return this.systemVariables;
    }

    void addTokenCost(Usage cost) {
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

    public void addResponseChoiceMessages(List<LLMMessage> messages) {
        messages.forEach(this::addResponseChoiceMessage);
    }

    @SuppressWarnings("unchecked")
    void addResponseChoiceMessage(LLMMessage message) {
        this.messages.add(message);
        this.getMessageUpdatedEventListener().ifPresent(v -> v.eventHandler((T) this, message));
        if (this.parent != null) {
            this.parent.addResponseChoiceMessage(message);
        }
    }
}
