package ai.core.api.mcp.schema.tool;

import ai.core.api.mcp.schema.Role;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ImageContent {
    @Property(name = "audience")
    public List<Role> audience;

    @Property(name = "priority")
    public Double priority;

    @Property(name = "data")
    public String data;

    @Property(name = "mimeType")
    public String mimeType;
}
