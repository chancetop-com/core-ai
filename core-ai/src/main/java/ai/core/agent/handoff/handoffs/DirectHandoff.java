package ai.core.agent.handoff.handoffs;

import ai.core.agent.AgentGroup;
import ai.core.agent.Node;
import ai.core.agent.handoff.Handoff;
import ai.core.agent.handoff.HandoffType;
import ai.core.agent.planning.Planning;
import ai.core.agent.planning.plannings.DefaultPlanningResult;
import ai.core.termination.Termination;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class DirectHandoff implements Handoff {

    @Override
    public void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables) {
        var currentAgent = agentGroup.getCurrentAgent();
        var directPlanning = new DefaultPlanningResult();
        directPlanning.name = getNextAgentNameOf(agentGroup.getAgents(), currentAgent == null ? null : currentAgent.getName());
        if (directPlanning.name.isEmpty()) {
            directPlanning.nextStep = Termination.DEFAULT_TERMINATION_WORD;
        }
        directPlanning.planning = "direct handoff to " + directPlanning.name;
        directPlanning.query = agentGroup.getOutput() == null ? agentGroup.getInput() : agentGroup.getOutput();
        planning.directPlanning(directPlanning);
    }

    @Override
    public HandoffType getType() {
        return HandoffType.DIRECT;
    }

    private String getNextAgentNameOf(List<Node<?>> agents, String name) {
        if (name == null) return agents.getFirst().getName();
        for (var node: agents) {
            if (node.getName().equals(name)) {
                var index = agents.indexOf(node);
                if (index == agents.size() - 1) {
                    return agents.getFirst().getName();
                }
                return agents.get(index + 1).getName();
            }
        }
        return "";
    }
}
