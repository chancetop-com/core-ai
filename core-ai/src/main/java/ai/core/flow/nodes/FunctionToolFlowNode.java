package ai.core.flow.nodes;

import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.tool.ToolCall;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class FunctionToolFlowNode extends ToolFlowNode<FunctionToolFlowNode> {

    public FunctionToolFlowNode() {

    }

    public FunctionToolFlowNode(String id, String name, ToolCall toolCall) {
        super(id, name, "FunctionTool", "Function Tool", FunctionToolFlowNode.class, toolCall);
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
    public String serialization(ToolFlowNode<FunctionToolFlowNode> node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(ToolFlowNode<FunctionToolFlowNode> node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends ToolFlowNode.Domain<Domain> {
        @Override
        public Domain from(ToolFlowNode<?> node) {
            this.fromToolBase(node);
            return this;
        }

        @Override
        public void setupNode(ToolFlowNode<?> node) {
            this.setupToolNodeBase(node);
        }
    }
}
