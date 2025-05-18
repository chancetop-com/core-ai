package ai.core.api.mcp.schema.tool;

import ai.core.api.mcp.JsonSchema;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class Tool {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "inputSchema")
    public JsonSchema inputSchema;
}
