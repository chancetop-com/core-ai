package ai.core.api.server.agent;

import core.framework.api.json.Property;

/**
 * Portable skill reference inside an agent export. The id only works in the exporting environment;
 * import resolves by qualified_name first, then by a unique name.
 */
public class AgentSkillRefView {
    @Property(name = "id")
    public String id;

    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "name")
    public String name;
}
