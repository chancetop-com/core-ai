package ai.core.api.server.agent;

import core.framework.api.json.Property;

/**
 * Portable sub-agent reference inside an agent export. Import resolves by id first, then by name
 * among the importing user's own agents.
 */
public class AgentSubAgentRefView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;
}
