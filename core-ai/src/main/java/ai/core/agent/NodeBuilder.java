package ai.core.agent;

import ai.core.agent.formatter.Formatter;
import ai.core.agent.listener.ChainNodeStatusChangedEventListener;
import ai.core.agent.listener.MessageUpdatedEventListener;
import ai.core.persistence.Persistence;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.SystemVariables;
import ai.core.rag.LongQueryHandler;
import ai.core.termination.Termination;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public abstract class NodeBuilder<B extends NodeBuilder<B, T>, T extends Node<T>> {
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
    Integer maxRound;

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

    public B maxRound(Integer maxRound) {
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

    public B longQueryHandler(LongQueryHandler longQueryHandler) {
        this.longQueryHandler = longQueryHandler;
        return self();
    }

    public B parent(Node<?> parent) {
        this.parent = parent;
        return self();
    }

    public void build(T node) {
        validation();
        if (maxRound == null) maxRound = 0;
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
        node.setLongQueryHandler(this.longQueryHandler);
        node.setParentNode(this.parent);
        node.updateNodeStatus(NodeStatus.INITED);
        var systemVariables = node.getSystemVariables();
        systemVariables.put(SystemVariables.NODE_NAME, this.name);
        systemVariables.put(SystemVariables.NODE_DESCRIPTION, this.description);
        systemVariables.put(SystemVariables.NODE_MAX_ROUND, this.maxRound);
        systemVariables.put(SystemVariables.TERMINATE_WORD, Termination.DEFAULT_TERMINATION_WORD);
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
