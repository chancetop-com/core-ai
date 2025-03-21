package ai.core.agent;

import ai.core.agent.formatter.Formatter;
import ai.core.agent.listener.ChainNodeStatusChangedEventListener;
import ai.core.agent.listener.MessageUpdatedEventListener;
import ai.core.llm.providers.inner.CompletionResponse;
import ai.core.llm.providers.inner.Message;
import ai.core.persistence.Persistence;
import ai.core.persistence.PersistenceProvider;
import ai.core.rag.LongQueryHandler;
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
    private List<Termination> terminations;
    private Formatter formatter;
    private MessageUpdatedEventListener<T> messageUpdatedEventListener;
    private Map<NodeStatus, ChainNodeStatusChangedEventListener<T>> statusChangedEventListeners;
    private NodeStatus nodeStatus;
    private Persistence<T> persistence;
    private PersistenceProvider persistenceProvider;
    private LongQueryHandler longQueryHandler;
    private String input;
    private String output;
    private String rawOutput;
    private int round;
    private int maxRound;
    private int currentTokens;
    private Node<?> parent;
    private final List<Message> messages;

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

    public void addMessageUpdatedEventListener(MessageUpdatedEventListener<T> listener) {
        if (listener == null) return;
        messageUpdatedEventListener = listener;
    }

    @SuppressWarnings("unchecked")
    public void updateNodeStatus(NodeStatus nodeStatus) {
        this.nodeStatus = nodeStatus;
        getStatusChangedEventListener(nodeStatus).ifPresent(listener -> listener.eventHandler((T) this));
    }

    boolean terminateCheck() {
        return !terminations.isEmpty() && terminations.stream().anyMatch(v -> v.terminate(this));
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

    public int getRound() {
        return this.round;
    }

    public int getMaxRound() {
        return this.maxRound;
    }

    public int getCurrentTokens() {
        return this.currentTokens;
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

    public Formatter getAgentFormatter() {
        return this.formatter;
    }

    public LongQueryHandler getLongQueryRagHandler() {
        return this.longQueryHandler;
    }

    public void clearShortTermMemory() {
        this.clearMessages();
        this.setInput(null);
        this.setOutput(null);
        this.setRawOutput(null);
        this.setRound(0);
        this.nodeStatus = NodeStatus.INITED;
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
            return execute(query, variables);
        } catch (Exception e) {
            updateNodeStatus(NodeStatus.FAILED);
            throw new RuntimeException(Strings.format("Run node {}<{}> failed: {}, raw request/response: {}, {}", this.name, this.id, e.getMessage(), getInput(), getRawOutput()), e);
        }
    }

    abstract String execute(String query, Map<String, Object> variables);

    abstract void setChildrenParentNode();

    void setRawOutput(String output) {
        this.rawOutput = output;
    }

    void setOutput(String output) {
        this.output = output;
    }

    void setName(String name) {
        this.name = name;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setNodeType(NodeType type) {
        this.nodeType = type;
    }

    void setRound(int round) {
        this.round = round;
    }

    void setMaxRound(int maxRound) {
        this.maxRound = maxRound;
    }

    void setLongQueryRagHandler(LongQueryHandler longQueryHandler) {
        this.longQueryHandler = longQueryHandler;
    }

    void setTerminations(List<Termination> terminations) {
        this.terminations = terminations;
    }

    void setFormatter(Formatter formatter) {
        this.formatter = formatter;
    }

    void setStatusChangedEventListeners(Map<NodeStatus, ChainNodeStatusChangedEventListener<T>> listeners) {
        this.statusChangedEventListeners = listeners;
    }

    void setMessageUpdatedEventListener(MessageUpdatedEventListener<T> listener) {
        this.messageUpdatedEventListener = listener;
    }

    void setPersistence(Persistence<T> persistence) {
        this.persistence = persistence;
    }

    void setPersistenceProvider(PersistenceProvider persistenceProvider) {
        this.persistenceProvider = persistenceProvider;
    }

    void addMessage(Message message) {
        this.messages.add(message);
    }

    void addMessages(List<Message> messages) {
        this.messages.addAll(messages);
    }

    void clearMessages() {
        this.messages.clear();
    }

    void setInput(String input) {
        this.input = input;
    }

    void addTokenCount(int count) {
        this.currentTokens += count;
        if (this.parent != null) {
            this.parent.addTokenCount(count);
        }
    }

    void setParentNode(Node<?> parent) {
        this.parent = parent;
    }

    void addTermination(Termination termination) {
        if (termination == null) return;
        this.terminations.add(termination);
    }

    void addTerminations(List<Termination> terminations) {
        if (terminations == null || terminations.isEmpty()) return;
        this.terminations.addAll(terminations);
    }

    void addResponseChoiceMessages(List<Message> messages) {
        messages.forEach(this::addResponseChoiceMessage);
    }

    @SuppressWarnings("unchecked")
    void addResponseChoiceMessage(Message message) {
        this.messages.add(message);
        this.getMessageUpdatedEventListener().ifPresent(v -> v.eventHandler((T) this, message));
    }

    public abstract static class Builder<B extends Builder<B, T>, T extends Node<T>> {
        String name;
        String description;
        Formatter formatter;
        List<Termination> terminations;
        NodeType nodeType;
        MessageUpdatedEventListener<T> messageUpdatedEventListener;
        Map<NodeStatus, ChainNodeStatusChangedEventListener<T>> statusChangedEventListeners;
        Persistence<T> persistence;
        PersistenceProvider persistenceProvider;
        LongQueryHandler longQueryHandler;
        Node<?> parent;
        int maxRound;

        // This method needs to be overridden in the subclass Builders
        protected abstract B self();

        public B name(String name) {
            this.name = name;
            return self();
        }

        public B description(String description) {
            this.description = description;
            return self();
        }

        public B nodeType(NodeType nodeType) {
            this.nodeType = nodeType;
            return self();
        }

        public B formatter(Formatter formatter) {
            this.formatter = formatter;
            return self();
        }

        public B maxRound(int maxRound) {
            this.maxRound = maxRound;
            return self();
        }

        public B terminations(List<Termination> terminations) {
            this.terminations = terminations;
            return self();
        }

        public B messageUpdatedEventListener(MessageUpdatedEventListener<T> messageUpdatedEventListener) {
            this.messageUpdatedEventListener = messageUpdatedEventListener;
            return self();
        }

        public B statusChangedEventListeners(Map<NodeStatus, ChainNodeStatusChangedEventListener<T>> statusChangedEventListeners) {
            this.statusChangedEventListeners = statusChangedEventListeners;
            return self();
        }

        public B persistence(Persistence<T> persistence) {
            this.persistence = persistence;
            return self();
        }

        public B persistenceProvider(PersistenceProvider persistenceProvider) {
            this.persistenceProvider = persistenceProvider;
            return self();
        }

        public B longQueryRagHandler(LongQueryHandler longQueryHandler) {
            this.longQueryHandler = longQueryHandler;
            return self();
        }

        public B parent(Node<?> parent) {
            this.parent = parent;
            return self();
        }

        public void build(T node) {
            validation();
            node.setName(this.name);
            node.setDescription(this.description);
            node.setFormatter(this.formatter);
            node.setNodeType(this.nodeType);
            node.addTerminations(this.terminations);
            node.setMaxRound(this.maxRound);
            node.addStatusChangedEventListeners(this.statusChangedEventListeners);
            node.setMessageUpdatedEventListener(this.messageUpdatedEventListener);
            node.setPersistence(this.persistence);
            node.setPersistenceProvider(this.persistenceProvider);
            node.setLongQueryRagHandler(this.longQueryHandler);
            node.setParentNode(this.parent);
            node.updateNodeStatus(NodeStatus.INITED);
        }

        private void validation() {
            if (this.name == null || this.description == null) {
                throw new IllegalArgumentException("name and description is required");
            }
            if (this.nodeType == null) {
                throw new IllegalArgumentException("nodeType is required");
            }
        }
    }
}
