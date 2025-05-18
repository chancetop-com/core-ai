package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CallToolRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "arguments")
    public String arguments;
}
