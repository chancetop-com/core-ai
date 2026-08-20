package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ProjectAgentStatView {
    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "tokens")
    public Long tokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @Property(name = "count")
    public Long count;
}
