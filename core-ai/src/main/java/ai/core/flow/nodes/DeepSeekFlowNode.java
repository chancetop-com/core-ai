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
public class DeepSeekFlowNode extends LLMFlowNode<DeepSeekFlowNode> {

    public DeepSeekFlowNode() {
        super("DeepSeek", "DeepSeek AI Model Provider", DeepSeekFlowNode.class);
    }
    public DeepSeekFlowNode(String id, String name) {
        super(id, name, "DeepSeek", "DeepSeek AI Model Provider", DeepSeekFlowNode.class);
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        if (getModel() == null) setModel(LLMProviders.getProviderDefaultChatModel(LLMProviderType.DEEPSEEK));
        if (getTemperature() == null) setTemperature(0.7);
        setLlmProviderConfig(new LLMProviderConfig(getModel(), getTemperature(), getEmbeddingModel()));
        setLlmProvider(getLlmProviders().getProvider(LLMProviderType.DEEPSEEK));
        getLlmProvider().setConfig(getLlmProviderConfig());
    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(LLMFlowNode<DeepSeekFlowNode> node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(LLMFlowNode<DeepSeekFlowNode> node, String c) {
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
