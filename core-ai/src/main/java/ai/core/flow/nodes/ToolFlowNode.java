package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeType;
import ai.core.tool.ToolCall;

/**
 * @author stephen
 */
public abstract class ToolFlowNode<T extends ToolFlowNode<T>> extends FlowNode<ToolFlowNode<T>> {
    private Integer topK = 5;
    private Double threshold = 0d;
    private ToolCall toolCall;

    public ToolFlowNode() {

    }

    public ToolFlowNode(String id, String name, String typeName, String typeDescription, Class<?> cls) {
        super(id, name, typeName, typeDescription, FlowNodeType.RAG, cls);
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }

    public void setToolCall(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    public abstract static class Domain<T extends Domain<T>> extends FlowNode.Domain<Domain<T>> {
        public Integer topK = 5;
        public Double threshold = 0d;

        public void fromRagBase(ToolFlowNode<?> node) {
            this.topK = node.topK;
            this.threshold = node.threshold;
            from((FlowNode<?>) node);
        }

        public void setupRagNodeBase(ToolFlowNode<?> node) {
            node.topK = this.topK;
            node.threshold = this.threshold;
            setupNode((FlowNode<?>) node);
        }

        @Override
        public T from(FlowNode<?> node) {
            this.fromBase(node);
            return null;
        }

        public abstract T from(ToolFlowNode<?> node);

        @Override
        public void setupNode(FlowNode<?> node) {
            setupNodeBase(node);
        }

        public abstract void setupNode(ToolFlowNode<?> node);
    }
}
