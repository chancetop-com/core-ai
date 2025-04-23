package ai.core.flow.nodes;

import ai.core.agent.handoff.Handoff;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeType;

/**
 * @author stephen
 */
public abstract class HandoffFlowNode<T extends HandoffFlowNode<T>> extends FlowNode<HandoffFlowNode<T>> {
    private Handoff handoff;

    public HandoffFlowNode(String typeName, String typeDescription, Class<?> cls) {
        super(typeName, typeDescription, FlowNodeType.HANDOFF, cls);
    }

    public HandoffFlowNode(String id, String name, String typeName, String typeDescription, Class<?> cls) {
        super(id, name, typeName, typeDescription, FlowNodeType.HANDOFF, cls);
    }

    public Handoff getHandoff() {
        return handoff;
    }

    public void setHandoff(Handoff handoff) {
        this.handoff = handoff;
    }

    public abstract static class Domain<T extends Domain<T>> extends FlowNode.Domain<Domain<T>> {

        public void fromHandoffBase(HandoffFlowNode<?> node) {
            from((FlowNode<?>) node);
        }

        public void setupHandoffNodeBase(HandoffFlowNode<?> node) {
            setupNode((FlowNode<?>) node);
        }

        @Override
        public T from(FlowNode<?> node) {
            this.fromBase(node);
            return null;
        }

        public abstract T from(HandoffFlowNode<?> node);

        @Override
        public void setupNode(FlowNode<?> node) {
            setupNodeBase(node);
        }

        public abstract void setupNode(HandoffFlowNode<?> node);
    }
}
