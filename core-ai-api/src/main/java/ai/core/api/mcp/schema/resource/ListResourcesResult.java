package ai.core.api.mcp.schema.resource;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListResourcesResult {
    @Property(name = "resources")
    public List<Resource> resources;

    @Property(name = "nextCursor")
    public String nextCursor;
}
