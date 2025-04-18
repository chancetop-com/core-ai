package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviders;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class DeepSeekFlowNode extends LLMFlowNode<DeepSeekFlowNode> {
    LLMProviders llmProviders;

    public DeepSeekFlowNode() {

    }

    public DeepSeekFlowNode(String id, String name, LLMProviders llmProviders) {
        super(id, name, "DeepSeek", "DeepSeek AI Model Provider", DeepSeekFlowNode.class);
        this.llmProviders = llmProviders;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings) {
        if (!getInitialized()) {
            if (getModel() == null) setModel("deepseek-chat");
            if (getTemperature() == null) setTemperature(0.7);
            setLlmProviderConfig(new LLMProviderConfig(getModel(), getTemperature(), getEmbeddingModel()));
            setLlmProvider(llmProviders.getProviderByName("DeepSeek"));
            getLlmProvider().setConfig(getLlmProviderConfig());
        }
        setInitialized(true);
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
