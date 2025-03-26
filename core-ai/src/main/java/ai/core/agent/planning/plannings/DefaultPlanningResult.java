package ai.core.agent.planning.plannings;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class DefaultPlanningResult {
    @Property(name = "planning")
    public String planning;

    @Property(name = "next_agent_name")
    public String name;

    @Property(name = "next_query")
    public String query;

    @Property(name = "next_step_action")
    public String nextStep;
}
