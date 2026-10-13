package ai.core.api.server.comparison;

import core.framework.api.json.Property;

/**
 * Creates a version as a snapshot: of the agent's current published config by default, or a copy of an
 * existing version when copy_from_version_id is set.
 *
 * @author stephen
 */
public class CreateAgentVersionRequest {
    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "label")
    public String label;

    @Property(name = "copy_from_version_id")
    public String copyFromVersionId;
}
