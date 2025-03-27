package ai.core.agent.planning;

import ai.core.agent.Agent;

import java.util.Map;

/**
 * @author stephen
 */
public interface Planning {
    String agentPlanning(Agent agent, String query, Map<String, Object> variables);

    <T> void directPlanning(T instance);

    <T> T explainPlanning(String planningText, Class<T> instanceClass);

    String nextAgentName();

    String nextQuery();

    String nextAction();

    String planningText();
}
