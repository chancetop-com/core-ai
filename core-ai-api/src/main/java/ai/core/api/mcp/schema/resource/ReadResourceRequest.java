package ai.core.api.mcp.schema.resource;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ReadResourceRequest {
    @Property(name = "uri")
    public String uri;
}
