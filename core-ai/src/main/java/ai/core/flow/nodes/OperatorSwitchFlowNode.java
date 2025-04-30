package ai.core.flow.nodes;

import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.flow.FlowNodeType;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class OperatorSwitchFlowNode extends FlowNode<OperatorSwitchFlowNode> {
    private List<String> values;

    public OperatorSwitchFlowNode() {
        super("Switch", "Switch Node", FlowNodeType.OPERATOR_SWITCH, OperatorSwitchFlowNode.class);
    }

    public OperatorSwitchFlowNode(String id, String name) {
        super(id, name, "Switch", "Switch Node", FlowNodeType.OPERATOR_SWITCH, OperatorSwitchFlowNode.class);
    }

    public void setValues(List<String> values) {
        this.values = values;
    }

    public List<String> getValues() {
        return values;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {

    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(OperatorSwitchFlowNode node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(OperatorSwitchFlowNode node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends FlowNode.Domain<Domain> {
        public List<String> values;

        @Override
        public Domain from(FlowNode<?> node) {
            this.fromBase(node);
            this.values = ((OperatorSwitchFlowNode) node).getValues();
            return this;
        }

        @Override
        public void setupNode(FlowNode<?> node) {
            this.setupNodeBase(node);
            ((OperatorSwitchFlowNode) node).setValues(this.values);
        }
    }
}
