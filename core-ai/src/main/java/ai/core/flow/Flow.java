package ai.core.flow;

import ai.core.flow.listener.FlowNodeChangedEventListener;
import ai.core.flow.listener.FlowNodeOutputUpdatedEventListener;
import ai.core.flow.nodes.LLMFlowNode;
import ai.core.flow.nodes.RagFlowNode;
import ai.core.llm.LLMProviders;
import ai.core.persistence.Persistence;
import ai.core.rag.VectorStores;
import core.framework.util.Strings;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
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
    List<FlowNode<?>> nodes = List.of();
    List<FlowEdge<?>> edges = List.of();
    Persistence<Flow> persistence;
    String currentNodeId;
    String currentInput;
    Map<String, Object> currentVariables = Map.of();
    FlowNodeChangedEventListener flowNodeChangedEventListener;
    FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener;
    private LLMProviders llmProviders;
    private VectorStores vectorStores;

    public Flow() {
        this.id = UUID.randomUUID().toString();
        this.persistence = new FlowPersistence();
    }

    public Flow(String id) {
        this.id = id;
        this.persistence = new FlowPersistence();
    }

    public String execute(String nodeId, String input, Map<String, Object> variables) {
        try {
            return run(nodeId, input, variables);
        } catch (Exception e) {
            var currentNode = getNodeById(currentNodeId);
            return Strings.format("Exception at {}: {}", currentNode.getName(), e.getMessage());
        }
    }

    public String run(String nodeId, String input, Map<String, Object> variables) {
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
            // init llm providers
            if (setting instanceof LLMFlowNode<?> llmFlowNode && llmProviders != null) {
                llmFlowNode.setLlmProviders(llmProviders);
            }
            // init rag providers
            if (setting instanceof RagFlowNode<?> ragFlowNode && vectorStores != null) {
                ragFlowNode.setVectorStores(vectorStores);
            }
            var settings = getNodeSettings(setting);
            if (!settings.isEmpty()) initSettings(settings);
            setting.initialize(settings, edges);
        });
    }

    public void check() {
        if (persistence == null) throw new IllegalArgumentException("Persistence is not set");
        if (nodes.isEmpty()) throw new IllegalArgumentException("Nodes cannot be empty");
        if (edges.isEmpty()) throw new IllegalArgumentException("Edges cannot be empty");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Flow ID cannot be null or empty");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Flow name cannot be null or empty");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Flow description cannot be null or empty");
        if (nodes.stream().anyMatch(v -> v.type == FlowNodeType.LLM) && llmProviders == null) {
            throw new IllegalArgumentException("LLMProviders cannot be null if LLM node is present");
        }
        if (nodes.stream().anyMatch(v -> v.type == FlowNodeType.RAG) && vectorStores == null) {
            throw new IllegalArgumentException("VectorStores cannot be null if RAG node is present");
        }
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

    public void setLlmProviders(LLMProviders llmProviders) {
        this.llmProviders = llmProviders;
    }

    public void setVectorStores(VectorStores vectorStores) {
        this.vectorStores = vectorStores;
    }

    public void setPersistence(Persistence<Flow> persistence) {
        this.persistence = persistence;
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
                .filter(edge -> edge.type == FlowEdgeType.CONNECTION)
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

    public static class Builder {
        private String id;
        private String name;
        private String description;
        private List<FlowNode<?>> nodes;
        private List<FlowEdge<?>> edges;
        private FlowNodeChangedEventListener flowNodeChangedEventListener;
        private FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener;
        private LLMProviders llmProviders;
        private VectorStores vectorStores;

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

        public Builder flowNodeStatusChangedEventListener(FlowNodeChangedEventListener flowNodeChangedEventListener) {
            this.flowNodeChangedEventListener = flowNodeChangedEventListener;
            return this;
        }

        public Builder flowOutputUpdatedEventListener(FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener) {
            this.flowNodeOutputUpdatedEventListener = flowNodeOutputUpdatedEventListener;
            return this;
        }

        public Builder llmProviders(LLMProviders llmProviders) {
            this.llmProviders = llmProviders;
            return this;
        }

        public Builder vectorStores(VectorStores vectorStores) {
            this.vectorStores = vectorStores;
            return this;
        }

        public Flow build() {
            Flow flow = new Flow(id);
            flow.setName(name);
            flow.setDescription(description);
            flow.setNodes(nodes);
            flow.setEdges(edges);
            flow.setLlmProviders(llmProviders);
            flow.setVectorStores(vectorStores);
            flow.setFlowNodeStatusChangedEventListener(flowNodeChangedEventListener);
            flow.setFlowOutputUpdatedEventListener(flowNodeOutputUpdatedEventListener);
            return flow;
        }
    }
}
