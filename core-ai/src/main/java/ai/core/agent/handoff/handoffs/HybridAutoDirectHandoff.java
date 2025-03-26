package ai.core.agent.handoff.handoffs;

import ai.core.agent.AgentGroup;
import ai.core.agent.handoff.Handoff;
import ai.core.agent.planning.Planning;

import java.util.Map;

/**
 * @author stephen
 */
public class HybridAutoDirectHandoff implements Handoff {
    private final AutoHandoff autoHandoff = new AutoHandoff();
    private final DirectHandoff directHandoff = new DirectHandoff();

    @Override
    public void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables) {
        var currentAgent = agentGroup.getCurrentAgent();
        if (currentAgent != null && currentAgent.getNext() != null) {
            directHandoff.handoff(agentGroup, planning, variables);
            return;
        }
        autoHandoff.handoff(agentGroup, planning, variables);
    }
}
