package ai.core.flow.nodes;

import ai.core.agent.AgentGroup;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.flow.FlowNodeType;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentGroupFlowNode extends FlowNode<AgentGroupFlowNode> {
    private AgentGroup agentGroup;

    public AgentGroupFlowNode() {

    }

    public AgentGroupFlowNode(String id, String name) {
        super(id, name, "AgentGroup", "AI Agent Group Node", FlowNodeType.AGENT_GROUP, AgentGroupFlowNode.class);
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return new FlowNodeResult(agentGroup.run(input, variables));
    }

    @Override
    public void init(List<FlowNode<?>> settings) {

    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(AgentGroupFlowNode node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(AgentGroupFlowNode node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends FlowNode.Domain<Domain> {
        @Override
        public Domain from(FlowNode<?> node) {
            this.fromBase(node);
            return this;
        }

        @Override
        public void setupNode(FlowNode<?> node) {
            this.setupNodeBase(node);
        }
    }
}
