package ai.core.example.api.example;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class MCPToolCallRequest {
    @Property(name = "url")
    public String url;

    @Property(name = "query")
    public String query;
}
