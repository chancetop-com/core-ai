package ai.core.agent;

/**
 * @author stephen
 */
public interface Planning {
    void planning(Agent agent, String query);

    String nextAgentName();

    String nextQuery();

    String nextAction();
}
