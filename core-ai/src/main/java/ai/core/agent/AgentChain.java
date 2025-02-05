package ai.core.agent;

import ai.core.termination.Termination;
import core.framework.util.Lists;
import core.framework.util.Strings;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentChain extends Node<AgentChain> {

    public static Builder builder() {
        return new Builder();
    }

    // agent flow order by node in the list
    List<Node<?>> nodes;

    public AgentChain() {
        this.nodes = Lists.newArrayList();
    }

    public void addNode(Node<?> node) {
        nodes.add(node);
    }

    public String getConversationText() {
        var rst = new StringBuilder(getInput());
        rst.append("\n\n");
        for (var node : nodes) {
            if (node instanceof Agent agent) {
                rst.append(Strings.format("{}:\n{}\n\n", agent.getName(), agent.getOutput()));
            } else if (node instanceof AgentGroup group) {
                rst.append(Strings.format("{}:\n{}\n\n", group.getName(), group.getOutput()));
            } else if (node instanceof AgentChain agentChain) {
                rst.append(Strings.format("{}:\n{}\n\n", agentChain.getName(), agentChain.getOutput()));
            }
        }
        return rst.toString();
    }

    @Override
    String execute(String query, Map<String, Object> variables) {
        setInput(query);
        var input = query;

        for (var node: nodes) {
            if (node.getNodeStatus() == NodeStatus.FAILED || node.getNodeStatus() == NodeStatus.COMPLETED) continue;
            // current agent's output is the next agent's input
            input = node.run(input, variables);
            if (node.getNodeStatus() == NodeStatus.INITED) {
                updateNodeStatus(NodeStatus.RUNNING);
            }
            // terminate check
            if (terminateCheck()) {
                updateNodeStatus(NodeStatus.FAILED);
                return Termination.DEFAULT_TERMINATION_WORD;
            }
        }

        // last node's output is the chain's output
        setOutput(input);
        updateNodeStatus(NodeStatus.COMPLETED);
        return input;
    }

    public static class Builder extends Node.Builder<Builder, AgentChain> {
        List<Node<?>> nodes;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder chainNodes(List<Node<?>> nodes) {
            this.nodes = nodes;
            return this;
        }

        public AgentChain build() {
            var chain = new AgentChain();
            this.nodeType = NodeType.CHAIN;
            build(chain);
            chain.nodes = this.nodes;
            chain.setPersistence(new AgentChainPersistence());
            return chain;
        }
    }
}
