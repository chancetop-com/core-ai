package ai.core.api.server.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class TestApiToolRequest {
    @Property(name = "tool_id")
    public String toolId;

    @Property(name = "arguments")
    public String arguments;
}
