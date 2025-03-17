package ai.core.agent;

import java.util.Map;

/**
 * @author stephen
 */
public interface Planning {
    String planning(Agent agent, String query, Map<String, Object> variables);

    <T> T localPlanning(String planningText, Class<T> instanceClass);

    String nextAgentName();

    String nextQuery();

    String nextAction();

    String planningText();
}
