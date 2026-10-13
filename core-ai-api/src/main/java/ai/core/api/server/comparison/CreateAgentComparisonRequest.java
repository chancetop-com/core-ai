package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateAgentComparisonRequest {
    @Property(name = "agent_id")
    public String agentId;

    /** Saved version as variant A; null = the agent's current published config. */
    @Property(name = "version_a_id")
    public String versionAId;

    /** Saved version as variant B; null = a copy of variant A's source. */
    @Property(name = "version_b_id")
    public String versionBId;
}
