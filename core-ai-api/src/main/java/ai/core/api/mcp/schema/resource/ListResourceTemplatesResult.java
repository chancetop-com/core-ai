package ai.core.api.mcp.schema.resource;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListResourceTemplatesResult {
    @Property(name = "resourceTemplates")
    public List<ResourceTemplate> resourceTemplates;

    @Property(name = "nextCursor")
    public String nextCursor;
}
