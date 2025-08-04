package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class Content {
    @Property(name = "type")
    public ContentType type;

    @Property(name = "text")
    public String text;

    // image & audio
    @Property(name = "data")
    public String data;

    @Property(name = "mimeType")
    public String mimeType;

    // resource
    @Property(name = "name")
    public String name;

    @Property(name = "uri")
    public String uri;

    public enum ContentType {
        @Property(name = "text")
        TEXT,
        @Property(name = "image")
        IMAGE,
        @Property(name = "resource")
        RESOURCE
    }
}
