package ai.core.api.server.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class TestMcpToolRequest {
    @Property(name = "tool_name")
    public String toolName;

    @Property(name = "arguments")
    public String arguments;
}
