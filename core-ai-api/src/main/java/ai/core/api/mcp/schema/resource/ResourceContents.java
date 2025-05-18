package ai.core.api.mcp.schema.resource;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ResourceContents {
    @Property(name = "uri")
    public String uri;

    @Property(name = "mimeType")
    public String mimeType;

    @Property(name = "text")
    public String text;

    @Property(name = "blob")
    public String blob;
}
