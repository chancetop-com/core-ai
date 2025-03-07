package ai.core.agent.planning;

import ai.core.agent.Agent;
import ai.core.agent.Planning;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import core.framework.util.Strings;
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
        try {
            result = JSON.fromJSON(DefaultAgentPlanningResult.class, rst);
            logger.info("Planning: {}", rst);
            return rst;
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

    @Override
    public String planningText() {
        return result.planning;
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
