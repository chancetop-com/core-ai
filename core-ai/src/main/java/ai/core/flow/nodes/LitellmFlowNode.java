package ai.core.flow.nodes;

import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class LitellmFlowNode extends LLMFlowNode<LitellmFlowNode> {

    public LitellmFlowNode() {
        super("Litellm", "Litellm AI Model Provider", LitellmFlowNode.class);
    }
    public LitellmFlowNode(String id, String name) {
        super(id, name, "Litellm", "Litellm AI Model Provider", LitellmFlowNode.class);
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        if (getModel() == null) setModel(LLMProviders.getProviderDefaultChatModel(LLMProviderType.LITELLM));
        if (getTemperature() == null) setTemperature(0.7);
        setLlmProviderConfig(new LLMProviderConfig(getModel(), getTemperature(), getEmbeddingModel()));
        setLlmProvider(getLlmProviders().getProvider(LLMProviderType.LITELLM));
        getLlmProvider().setConfig(getLlmProviderConfig());
    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(LLMFlowNode<LitellmFlowNode> node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(LLMFlowNode<LitellmFlowNode> node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends LLMFlowNode.Domain<Domain> {
        @Override
        public Domain from(LLMFlowNode<?> node) {
            this.fromLLMBase(node);
            return this;
        }

        @Override
        public void setupNode(LLMFlowNode<?> node) {
            this.setupLLMNodeBase(node);
        }
    }
}
