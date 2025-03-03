package ai.core.agent;

import java.util.Map;

/**
 * @author stephen
 */
public interface Planning {
    void planning(Agent agent, String query, Map<String, Object> variables);

    String nextAgentName();

    String nextQuery();

    String nextAction();
}
