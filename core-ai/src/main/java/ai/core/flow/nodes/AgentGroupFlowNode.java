package ai.core.flow.nodes;

import ai.core.agent.AgentGroup;
import ai.core.agent.Node;
import ai.core.agent.handoff.Handoff;
import ai.core.agent.planning.plannings.DefaultPlanning;
import ai.core.flow.FlowEdge;
import ai.core.flow.FlowEdgeType;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.flow.FlowNodeType;
import ai.core.llm.LLMProvider;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class AgentGroupFlowNode extends FlowNode<AgentGroupFlowNode> {
    private String description;
    private Integer maxRound;

    // settings edge variables
    private LLMProvider llmProvider;
    private Handoff handoff;
    private final List<Node<?>> agents = new ArrayList<>();

    private AgentGroup agentGroup;

    public AgentGroupFlowNode() {

    }

    public AgentGroupFlowNode(String id, String name, String description, Integer maxRound) {
        super(id, name, "AgentGroup", "AI Agent Group Node", FlowNodeType.AGENT_GROUP, AgentGroupFlowNode.class);
        this.description = description;
        this.maxRound = maxRound;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return new FlowNodeResult(agentGroup.run(input, variables));
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        settings.forEach(setting -> {
            if (setting instanceof LLMFlowNode<?> llmFlowNode) {
                llmProvider = llmFlowNode.getLlmProvider();
            }
            if (setting instanceof AgentFlowNode agentFlowNode) {
                agents.add(agentFlowNode.getAgent());
                setupNext(agentFlowNode.getAgent(), agentFlowNode.getId(), settings, edges);
            }
            if (setting instanceof AgentGroupFlowNode agentGroupFlowNode) {
                agents.add(agentGroupFlowNode.getAgentGroup());
                setupNext(agentGroupFlowNode.getAgentGroup(), agentGroupFlowNode.getId(), settings, edges);
            }
            if (setting instanceof HandoffFlowNode<?> handoffFlowNode) {
                handoff = handoffFlowNode.getHandoff();
            }
        });
        this.agentGroup = AgentGroup.builder()
                .name(getName())
                .description(description)
                .maxRound(maxRound)
                .llmProvider(llmProvider)
                .agents(agents)
                .planning(new DefaultPlanning())
                .handoff(handoff).build();
    }

    private void setupNext(Node<?> agent, String id, List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        var nextFlowNodeOptional = findNextFlowNodeThroughEdgeById(id, edges, settings);
        nextFlowNodeOptional.ifPresent(node -> {
            if (node instanceof AgentFlowNode agentFlowNode1) {
                agent.setNext(agentFlowNode1.getAgent());
            }
            if (node instanceof AgentGroupFlowNode agentGroupFlowNode) {
                agent.setNext(agentGroupFlowNode.getAgentGroup());
            }
        });
    }

    private Optional<FlowNode<?>> findNextFlowNodeThroughEdgeById(String id, List<FlowEdge<?>> edges, List<FlowNode<?>> settings) {
        var settingsMap = settings.stream().collect(Collectors.toMap(FlowNode::getId, Function.identity()));
        return edges.stream()
                .filter(edge -> edge.getType() == FlowEdgeType.FLOW)
                .filter(edge -> edge.getSourceNodeId().equals(id))
                .<FlowNode<?>>map(edge -> settingsMap.get(edge.getTargetNodeId())).findFirst();
    }

    @Override
    public void check(List<FlowNode<?>> settings) {
        var llmExist = settings.stream().anyMatch(setting -> setting instanceof LLMFlowNode<?>);
        if (!llmExist) {
            throw new IllegalArgumentException("AgentGroupFlowNode must have LLMFlowNode as setting: " + this.getName());
        }
        var agentExist = settings.stream().anyMatch(setting -> setting instanceof AgentFlowNode);
        var agentGroupExist = settings.stream().anyMatch(setting -> setting instanceof AgentGroupFlowNode);
        if (!agentExist && !agentGroupExist) {
            throw new IllegalArgumentException("AgentGroupFlowNode must have AgentFlowNode or AgentGroupFlowNode as setting: " + this.getName());
        }
        var handoffExist = settings.stream().anyMatch(setting -> setting instanceof HandoffFlowNode<?>);
        if (!handoffExist) {
            throw new IllegalArgumentException("AgentGroupFlowNode must have HandoffFlowNode as setting: " + this.getName());
        }
    }

    public AgentGroup getAgentGroup() {
        return agentGroup;
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
        public String description;
        public Integer maxRound;

        @Override
        public Domain from(FlowNode<?> node) {
            this.fromBase(node);
            var agentGroupNode = (AgentGroupFlowNode) node;
            this.description = agentGroupNode.description;
            this.maxRound = agentGroupNode.maxRound;
            return this;
        }

        @Override
        public void setupNode(FlowNode<?> node) {
            this.setupNodeBase(node);
            var agentGroupNode = (AgentGroupFlowNode) node;
            agentGroupNode.description = this.description;
            agentGroupNode.maxRound = this.maxRound;
        }
    }
}
