package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class Content {
    @Property(name = "type")
    public ContentType type;

    @Property(name = "content")
    public String content;

    public enum ContentType {
        @Property(name = "text")
        TEXT,
        @Property(name = "image")
        IMAGE,
        @Property(name = "resource")
        RESOURCE
    }
}
