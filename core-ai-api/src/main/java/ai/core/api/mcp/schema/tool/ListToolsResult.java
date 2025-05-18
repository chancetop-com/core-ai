package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListToolsResult {
    @Property(name = "tools")
    public List<Tool> tools;

    @Property(name = "nextCursor")
    public String nextCursor;
}
