package ai.core.api.mcp.schema.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CallToolRequest {
    public static CallToolRequest of(String name, String arguments) {
        var request = new CallToolRequest();
        request.name = name;
        request.arguments = arguments;
        return request;
    }

    @Property(name = "name")
    public String name;

    @Property(name = "arguments")
    public String arguments;
}
