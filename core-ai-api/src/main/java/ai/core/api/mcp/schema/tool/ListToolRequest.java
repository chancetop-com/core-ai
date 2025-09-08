package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListToolRequest {
    @Property(name = "namespaces")
    public List<String> namespaces;
}
