package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.flow.FlowNodeType;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class ThrowErrorFlowNode extends FlowNode<ThrowErrorFlowNode> {
    private String message;

    public ThrowErrorFlowNode() {

    }

    public ThrowErrorFlowNode(String id, String name, String message) {
        super(id, name, "Error", "Throw Error Node", FlowNodeType.EXECUTE, ThrowErrorFlowNode.class);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        throw new ThrowErrorFlowNodeException(message);
    }

    @Override
    public void init(List<FlowNode<?>> settings) {

    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(ThrowErrorFlowNode node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(ThrowErrorFlowNode node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends FlowNode.Domain<Domain> {
        public String message;

        @Override
        public Domain from(FlowNode<?> node) {
            this.fromBase(node);
            this.message = ((ThrowErrorFlowNode) node).getMessage();
            return this;
        }

        @Override
        public void setupNode(FlowNode<?> node) {
            super.setupNodeBase(node);
            ((ThrowErrorFlowNode) node).message = this.message;
        }
    }
}
