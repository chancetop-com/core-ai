package ai.core.api.mcp.schema.resource;

import ai.core.api.mcp.schema.Role;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class EmbeddedResource {
    @Property(name = "audience")
    public List<Role> audience;

    @Property(name = "priority")
    public Double priority;

    @Property(name = "resource")
    public ResourceContents resource;
}
