package ai.core.flow.nodes;

import ai.core.agent.handoff.handoffs.AutoHandoff;
import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AutoHandoffFlowNode extends HandoffFlowNode<AutoHandoffFlowNode> {

    public AutoHandoffFlowNode() {

    }

    public AutoHandoffFlowNode(String id, String name) {
        super(id, name, "Auto Handoff", "Auto handoff by LLM in group planning", AutoHandoffFlowNode.class);
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        if (!getInitialized()) {
            var agentNode = settings.stream().filter(v -> v instanceof AgentFlowNode).findFirst().orElseThrow(() -> new IllegalArgumentException("AutoHandoffFlowNode must have AgentFlowNode as setting"));
            var agent = ((AgentFlowNode) agentNode).getAgent();
            setHandoff(new AutoHandoff(agent));
        }
        setInitialized(true);
    }

    @Override
    public void check(List<FlowNode<?>> settings) {
        var agentExist = settings.stream().anyMatch(setting -> setting instanceof AgentFlowNode);
        if (!agentExist) {
            throw new IllegalArgumentException("AutoHandoffFlowNode must have AgentFlowNode as setting");
        }
    }

    @Override
    public String serialization(HandoffFlowNode<AutoHandoffFlowNode> node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(HandoffFlowNode<AutoHandoffFlowNode> node, String c) {
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
