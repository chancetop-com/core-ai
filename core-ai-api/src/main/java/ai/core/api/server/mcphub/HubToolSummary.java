package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class HubToolSummary {
    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "server")
    public String server;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "score")
    public Integer score;

    @Property(name = "stale")
    public Boolean stale;
}
