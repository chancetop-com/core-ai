package ai.core.api.server.hubcatalog;

import core.framework.api.json.Property;

/**
 * The MCP server or API app the tools in a catalog came from.
 *
 * @author stephen
 */
public class HubCatalogSource {
    @Property(name = "kind")
    public String kind;

    @Property(name = "name")
    public String name;

    /** MCP connection state; null for an API app, which has no live connection. */
    @Property(name = "state")
    public String state;

    /** Tools (mcp) or operations (api) in the catalog's snapshot of this source. */
    @Property(name = "count")
    public Integer count;

    /** True when the source could not be refreshed and the snapshot is the previous one. */
    @Property(name = "stale")
    public Boolean stale;
}
