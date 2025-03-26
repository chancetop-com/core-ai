package ai.core.agent.planning.plannings;

import ai.core.agent.Agent;
import ai.core.agent.planning.Planning;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * @author stephen
 */
public class DefaultPlanning implements Planning {
    private final Logger logger = LoggerFactory.getLogger(DefaultPlanning.class);
    private DefaultPlanningResult result;

    @Override
    public String planning(Agent agent, String query, Map<String, Object> variables) {
        var rst = agent.run(query, variables);
        result = JSON.fromJSON(DefaultPlanningResult.class, rst);
        logger.info("Planning: {}", rst);
        return rst;
    }

    @Override
    public <T> void planning(T instance) {
        result = (DefaultPlanningResult) instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T localPlanning(String planningText, Class<T> instanceClass) {
        return (T) JSON.fromJSON(DefaultPlanningResult.class, planningText);
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
}
