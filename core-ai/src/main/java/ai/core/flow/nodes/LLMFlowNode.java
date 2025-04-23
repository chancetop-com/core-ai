package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeType;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviders;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public abstract class LLMFlowNode<T extends LLMFlowNode<T>> extends FlowNode<LLMFlowNode<T>> {
    private String model;
    private Double temperature;
    private String embeddingModel;
    private LLMProvider llmProvider;
    private LLMProviderConfig llmProviderConfig;
    private LLMProviders llmProviders;

    public LLMFlowNode(String typeName, String typeDescription, Class<?> cls) {
        super(typeName, typeDescription, FlowNodeType.LLM, cls);
    }

    public LLMFlowNode(String id, String name, String typeName, String typeDescription, Class<?> cls) {
        super(id, name, typeName, typeDescription, FlowNodeType.LLM, cls);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public LLMProvider getLlmProvider() {
        return llmProvider;
    }

    void setLlmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public LLMProviders getLlmProviders() {
        return llmProviders;
    }

    public void setLlmProviders(LLMProviders llmProviders) {
        this.llmProviders = llmProviders;
    }

    public LLMProviderConfig getLlmProviderConfig() {
        return llmProviderConfig;
    }

    void setLlmProviderConfig(LLMProviderConfig llmProviderConfig) {
        this.llmProviderConfig = llmProviderConfig;
    }

    public abstract static class Domain<T extends Domain<T>> extends FlowNode.Domain<Domain<T>> {
        @Property(name = "model")
        public String model;
        @Property(name = "temperature")
        public Double temperature;
        @Property(name = "embedding_model")
        public String embeddingModel;

        public void fromLLMBase(LLMFlowNode<?> node) {
            this.model = node.getModel();
            this.temperature = node.getTemperature();
            this.embeddingModel = node.getEmbeddingModel();
            from((FlowNode<?>) node);
        }

        public void setupLLMNodeBase(LLMFlowNode<?> node) {
            node.setModel(this.model);
            node.setTemperature(this.temperature);
            node.setEmbeddingModel(this.embeddingModel);
            setupNode((FlowNode<?>) node);
        }

        @Override
        public T from(FlowNode<?> node) {
            this.fromBase(node);
            return null;
        }

        public abstract T from(LLMFlowNode<?> node);

        @Override
        public void setupNode(FlowNode<?> node) {
            setupNodeBase(node);
        }

        public abstract void setupNode(LLMFlowNode<?> node);
    }
}
