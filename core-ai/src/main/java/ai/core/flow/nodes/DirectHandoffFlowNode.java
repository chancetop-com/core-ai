package ai.core.flow.nodes;

import ai.core.agent.handoff.handoffs.DirectHandoff;
import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class DirectHandoffFlowNode extends HandoffFlowNode<DirectHandoffFlowNode> {

    public DirectHandoffFlowNode() {
        super("Direct Handoff", "Direct handoff to next agent in group planning", DirectHandoffFlowNode.class);
    }

    public DirectHandoffFlowNode(String id, String name) {
        super(id, name, "Direct Handoff", "Direct handoff to next agent in group planning", DirectHandoffFlowNode.class);
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        setHandoff(new DirectHandoff());
    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(HandoffFlowNode<DirectHandoffFlowNode> node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(HandoffFlowNode<DirectHandoffFlowNode> node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends HandoffFlowNode.Domain<Domain> {
        @Override
        public Domain from(HandoffFlowNode<?> node) {
            this.fromHandoffBase(node);
            return this;
        }

        @Override
        public void setupNode(HandoffFlowNode<?> node) {
            this.setupHandoffNodeBase(node);
        }
    }
}
