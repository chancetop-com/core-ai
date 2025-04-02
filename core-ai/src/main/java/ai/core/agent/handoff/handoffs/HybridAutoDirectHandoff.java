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
public class HybridAutoDirectHandoff implements Handoff {
    private final AutoHandoff autoHandoff;
    private final DirectHandoff directHandoff = new DirectHandoff();

    public HybridAutoDirectHandoff(Agent moderator) {
        this.autoHandoff = new AutoHandoff(moderator);
    }

    public AutoHandoff getAutoHandoff() {
        return autoHandoff;
    }

    @Override
    public void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables) {
        var currentAgent = agentGroup.getCurrentAgent();
        if (currentAgent != null && currentAgent.getNext() != null) {
            directHandoff.handoff(agentGroup, planning, variables);
            return;
        }
        autoHandoff.handoff(agentGroup, planning, variables);
    }

    @Override
    public HandoffType getType() {
        return HandoffType.HYBRID;
    }
}
