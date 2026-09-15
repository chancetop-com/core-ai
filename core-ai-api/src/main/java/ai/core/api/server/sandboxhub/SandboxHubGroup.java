package ai.core.api.server.sandboxhub;

import core.framework.api.json.Property;

/**
 * Catalog group header: one MCP server, one API app, or one of the singleton kinds.
 *
 * @author stephen
 */
public class SandboxHubGroup {
    @Property(name = "kind")
    public String kind;

    @Property(name = "group")
    public String group;

    @Property(name = "path")
    public String path;

    @Property(name = "count")
    public Integer count;
}
