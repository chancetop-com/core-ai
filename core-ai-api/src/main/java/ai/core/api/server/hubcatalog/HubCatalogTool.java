package ai.core.api.server.hubcatalog;

import core.framework.api.json.Property;

/**
 * One callable entry of the catalog, whatever its kind. {@code path} is what a caller addresses it
 * by: {@code server/tool} for mcp, {@code app/service/operation} for api, the name for an agent or
 * an LLM_CALL definition.
 *
 * @author stephen
 */
public class HubCatalogTool {
    @Property(name = "kind")
    public String kind;

    @Property(name = "name")
    public String name;

    @Property(name = "path")
    public String path;

    /** MCP server or API app; null for agents and LLM_CALL definitions. */
    @Property(name = "group")
    public String group;

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "description")
    public String description;

    /** True when this tool comes from a source whose snapshot is stale. */
    @Property(name = "stale")
    public Boolean stale;
}
