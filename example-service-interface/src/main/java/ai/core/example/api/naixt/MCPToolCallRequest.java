package ai.core.example.api.naixt;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class MCPToolCallRequest {
    @Property(name = "host")
    public String host;

    @Property(name = "port")
    public Integer port;

    @Property(name = "query")
    public String query;
}
