package ai.core.agent.handoff.handoffs;

import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.handoff.Handoff;
import ai.core.agent.handoff.HandoffType;
import ai.core.agent.planning.Planning;

import java.util.Map;

/**
 * @author stephen
 */
public record AutoHandoff(Agent moderator) implements Handoff {

    @Override
    public void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables) {
        if (moderator.getParentNode() == null) {
            moderator.setParentNode(agentGroup);
        }
        agentGroup.setCurrentAgent(moderator);
        var text = planning.agentPlanning(moderator, agentGroup.getCurrentQuery(), variables);
        agentGroup.setRawOutput(text);
    }

    @Override
    public HandoffType getType() {
        return HandoffType.AUTO;
    }
}
