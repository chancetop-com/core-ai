package ai.core.flow;

import ai.core.flow.listener.FlowNodeChangedEventListener;
import ai.core.flow.listener.FlowNodeOutputUpdatedEventListener;
import ai.core.persistence.Persistence;
import ai.core.persistence.PersistenceProvider;
import core.framework.web.exception.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class Flow {
    public static Builder builder() {
        return new Builder();
    }

    String id;
    String name;
    String description;
    List<FlowNode<?>> nodes;
    List<FlowEdge<?>> edges;
    Persistence<Flow> persistence;
    PersistenceProvider persistenceProvider;
    String currentNodeId;
    String currentInput;
    Map<String, Object> currentVariables;
    FlowNodeChangedEventListener flowNodeChangedEventListener;
    FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener;

    public Flow() {

    }

    public Flow(String id, Persistence<Flow> persistence, PersistenceProvider persistenceProvider) {
        this.id = id;
        this.persistence = persistence;
        this.persistenceProvider = persistenceProvider;
    }

    public String execute(String nodeId, String input, Map<String, Object> variables) {
        validate();

        currentNodeId = nodeId;
        currentInput = input;
        currentVariables = variables;

        var currentNode = getNodeById(nodeId);

        if (flowNodeChangedEventListener != null) {
            flowNodeChangedEventListener.eventHandler(currentNode);
        }

        var currentNodeSettings = getNodeSettings(currentNode);
        if (!currentNodeSettings.isEmpty()) {
            initSettings(currentNodeSettings);
            currentNode.initialize(currentNodeSettings, edges);
        }

        var rst = new FlowNodeResult(input);
        if (List.of(
                FlowNodeType.EXECUTE,
                FlowNodeType.AGENT,
                FlowNodeType.AGENT_GROUP,
                FlowNodeType.TOOL,
                FlowNodeType.OPERATOR_FILTER).contains(currentNode.type)) {
            rst = currentNode.execute(input, variables);
        }

        if (flowNodeOutputUpdatedEventListener != null) {
            flowNodeOutputUpdatedEventListener.eventHandler(currentNode, currentInput, rst);
        }

        var nextNodes = getNextNodes(currentNode);
        if (nextNodes.isEmpty()) {
            return rst.text();
        }

        var nextNode = nextNodes.values().iterator().next();
        if (nextNodes.size() > 1) {
            nextNode = currentNode.selectNextNodeByEdgeValue(rst, nextNodes);
        }

        var query = rst.text();
        return execute(nextNode.id, query, variables);
    }

    private void initSettings(List<FlowNode<?>> currentNodeSettings) {
        currentNodeSettings.forEach(setting -> {
            var settings = getNodeSettings(setting);
            if (!settings.isEmpty()) initSettings(settings);
            setting.initialize(settings, edges);
        });
    }

    public void check() {
        if (persistence == null) throw new IllegalArgumentException("Persistence is not set");
        if (persistenceProvider == null) throw new IllegalArgumentException("Persistence provider is not set");
        if (nodes.isEmpty()) throw new IllegalArgumentException("Nodes cannot be empty");
        if (edges.isEmpty()) throw new IllegalArgumentException("Edges cannot be empty");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Flow ID cannot be null or empty");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Flow name cannot be null or empty");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Flow description cannot be null or empty");
        nodes.forEach(flowNode -> flowNode.check(getNodeSettings(flowNode)));
        edges.forEach(FlowEdge::check);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<FlowNode<?>> getNodes() {
        return nodes;
    }

    public List<FlowEdge<?>> getEdges() {
        return edges;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public Persistence<Flow> getPersistence() {
        return persistence;
    }

    public PersistenceProvider getPersistenceProvider() {
        return persistenceProvider;
    }

    public FlowNodeChangedEventListener getFlowNodeStatusChangedEventListener() {
        return flowNodeChangedEventListener;
    }

    public FlowNodeOutputUpdatedEventListener getFlowOutputUpdatedEventListener() {
        return flowNodeOutputUpdatedEventListener;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setNodes(List<FlowNode<?>> nodes) {
        this.nodes = nodes;
    }

    public void setEdges(List<FlowEdge<?>> edges) {
        this.edges = edges;
    }

    public void setPersistence(Persistence<Flow> persistence) {
        this.persistence = persistence;
    }

    public void setPersistenceProvider(PersistenceProvider persistenceProvider) {
        this.persistenceProvider = persistenceProvider;
    }

    public void setFlowNodeStatusChangedEventListener(FlowNodeChangedEventListener flowNodeChangedEventListener) {
        this.flowNodeChangedEventListener = flowNodeChangedEventListener;
    }

    public void setFlowOutputUpdatedEventListener(FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener) {
        this.flowNodeOutputUpdatedEventListener = flowNodeOutputUpdatedEventListener;
    }

    public void validate() {
        if (nodes.isEmpty()) throw new IllegalArgumentException("Nodes cannot be empty");
        check();
    }

    public Map<FlowEdge<?>, FlowNode<?>> getNextNodes(FlowNode<?> node) {
        return edges.stream()
                .filter(edge -> edge.type == FlowEdgeType.FLOW)
                .filter(edge -> edge.getSourceNodeId().equals(node.id))
                .collect(Collectors.toMap(edge -> edge, edge -> getNodeById(edge.getTargetNodeId())));
    }

    public List<FlowNode<?>> getNodeSettings(FlowNode<?> node) {
        return edges.stream()
                .filter(edge -> edge.type == FlowEdgeType.SETTING)
                .filter(edge -> edge.getSourceNodeId().equals(node.id))
                .<FlowNode<?>>map(edge -> getNodeById(edge.getTargetNodeId()))
                .toList();
    }

    public FlowNode<?> getNodeById(String nodeId) {
        return nodes.stream().filter(v -> v.id.equals(nodeId)).findFirst().orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    }

    public FlowNode<?> getNodeByName(String name) {
        return nodes.stream().filter(v -> v.id.equals(name)).findFirst().orElseThrow(() -> new NoSuchElementException("Node not found: " + name));
    }

    public String serialization() {
        if (persistence == null) throw new IllegalArgumentException("Persistence is not set");
        return persistence.serialization(this);
    }

    public void deserialization(String text) {
        if (persistence == null) throw new IllegalArgumentException("Persistence is not set");
        persistence.deserialization(this, text);
    }

    public void load(String id) {
        if (persistence == null) throw new IllegalArgumentException("Persistence is not set");
        if (persistenceProvider == null) throw new IllegalArgumentException("Persistence provider is not set");
        persistence.deserialization(this, persistenceProvider.load(id).orElseThrow(() -> new NotFoundException("Flow not found: " + id)));
    }

    public void save() {
        if (persistence == null) throw new IllegalArgumentException("Persistence is not set");
        if (persistenceProvider == null) throw new IllegalArgumentException("Persistence provider is not set");
        persistenceProvider.save(id, persistence.serialization(this));
    }

    public static class Builder {
        private String id;
        private String name;
        private String description;
        private List<FlowNode<?>> nodes;
        private List<FlowEdge<?>> edges;
        private Persistence<Flow> persistence;
        private PersistenceProvider persistenceProvider;
        private FlowNodeChangedEventListener flowNodeChangedEventListener;
        private FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder nodes(List<FlowNode<?>> nodes) {
            this.nodes = nodes;
            return this;
        }

        public Builder edges(List<FlowEdge<?>> edges) {
            this.edges = edges;
            return this;
        }

        public Builder persistence(Persistence<Flow> persistence) {
            this.persistence = persistence;
            return this;
        }

        public Builder persistenceProvider(PersistenceProvider persistenceProvider) {
            this.persistenceProvider = persistenceProvider;
            return this;
        }

        public Builder flowNodeStatusChangedEventListener(FlowNodeChangedEventListener flowNodeChangedEventListener) {
            this.flowNodeChangedEventListener = flowNodeChangedEventListener;
            return this;
        }

        public Builder flowOutputUpdatedEventListener(FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener) {
            this.flowNodeOutputUpdatedEventListener = flowNodeOutputUpdatedEventListener;
            return this;
        }

        public Flow build() {
            Flow flow = new Flow(id, persistence, persistenceProvider);
            flow.setName(name);
            flow.setDescription(description);
            flow.setNodes(nodes);
            flow.setEdges(edges);
            flow.setFlowNodeStatusChangedEventListener(flowNodeChangedEventListener);
            flow.setFlowOutputUpdatedEventListener(flowNodeOutputUpdatedEventListener);
            return flow;
        }
    }
}
