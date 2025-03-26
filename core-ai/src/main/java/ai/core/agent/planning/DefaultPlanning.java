package ai.core.agent.planning;

import ai.core.agent.Agent;
import ai.core.agent.Planning;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * @author stephen
 */
public class DefaultPlanning implements Planning {
    private final Logger logger = LoggerFactory.getLogger(DefaultPlanning.class);
    private DefaultAgentPlanningResult result;

    @Override
    public String planning(Agent agent, String query, Map<String, Object> variables) {
        var rst = agent.run(query, variables);
        result = JSON.fromJSON(DefaultAgentPlanningResult.class, rst);
        logger.info("Planning: {}", rst);
        return rst;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T localPlanning(String planningText, Class<T> instanceClass) {
        return (T) JSON.fromJSON(DefaultAgentPlanningResult.class, planningText);
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

    @Override
    public String planningText() {
        return result.planning;
    }

    public static class DefaultAgentPlanningResult {
        @Property(name = "planning")
        public String planning;

        @Property(name = "next_agent_name")
        public String name;

        @Property(name = "next_query")
        public String query;

        @Property(name = "next_step_action")
        public String nextStep;
    }
}
