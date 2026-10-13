package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateAgentComparisonRequest {
    @Property(name = "agent_id")
    public String agentId;
}
