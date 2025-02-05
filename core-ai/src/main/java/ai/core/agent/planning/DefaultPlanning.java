package ai.core.agent.planning;

import ai.core.agent.Agent;
import ai.core.agent.Planning;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import core.framework.util.Strings;

/**
 * @author stephen
 */
public class DefaultPlanning implements Planning {
    private DefaultAgentPlanningResult result;

    @Override
    public void planning(Agent agent, String query) {
        var rst = agent.run(query, null);
        try {
            result = JSON.fromJSON(DefaultAgentPlanningResult.class, rst);
        } catch (Exception e) {
            throw new RuntimeException(Strings.format("Failed to moderate: {}", rst), e);
        }
    }

    @Override
    public String nextAgentName() {
        return result.name;
    }

    @Override
    public String nextQuery() {
        return result.query;
    }

    @Override
    public String nextAction() {
        return result.nextStep;
    }

    public static class DefaultAgentPlanningResult {
        @Property(name = "planning")
        public String planning;

        @Property(name = "name")
        public String name;

        @Property(name = "query")
        public String query;

        @Property(name = "next_step")
        public String nextStep;
    }
}
