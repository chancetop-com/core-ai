package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CallToolResult {
    @Property(name = "content")
    public List<Content> content;

    @Property(name = "isError")
    public Boolean isError;
}
