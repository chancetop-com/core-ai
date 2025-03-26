package ai.core.agent.handoff;

import ai.core.agent.AgentGroup;
import ai.core.agent.planning.Planning;

import java.util.Map;

/**
 * @author stephen
 */
public interface Handoff {
    void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables);
}
