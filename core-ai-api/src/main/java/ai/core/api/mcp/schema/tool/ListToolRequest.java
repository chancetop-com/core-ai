package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListToolRequest {
    public static ListToolRequest of(List<String> namespaces) {
        var request = new ListToolRequest();
        request.namespaces = namespaces;
        return request;
    }

    @Property(name = "namespaces")
    public List<String> namespaces;
}
