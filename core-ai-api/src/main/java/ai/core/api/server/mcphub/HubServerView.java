package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class HubServerView {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "category")
    public String category;

    @Property(name = "state")
    public String state;

    @Property(name = "tool_count")
    public Integer toolCount;

    @Property(name = "stale")
    public Boolean stale;
}
