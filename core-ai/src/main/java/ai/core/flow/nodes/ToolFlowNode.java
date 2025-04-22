package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeType;
import ai.core.tool.ToolCall;

/**
 * @author stephen
 */
public abstract class ToolFlowNode<T extends ToolFlowNode<T>> extends FlowNode<ToolFlowNode<T>> {
    ToolCall toolCall;

    public ToolFlowNode() {

    }

    public ToolFlowNode(String id, String name, String typeName, String typeDescription, Class<?> cls, ToolCall toolCall) {
        super(id, name, typeName, typeDescription, FlowNodeType.TOOL, cls);
        this.toolCall = toolCall;
    }

    public ToolCall getToolCall() {
        return toolCall;
    }

    public abstract static class Domain<T extends Domain<T>> extends FlowNode.Domain<Domain<T>> {

        public void fromToolBase(ToolFlowNode<?> node) {
            from((FlowNode<?>) node);
        }

        public void setupToolNodeBase(ToolFlowNode<?> node) {
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
