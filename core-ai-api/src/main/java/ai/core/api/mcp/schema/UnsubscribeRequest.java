package ai.core.api.mcp.schema;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UnsubscribeRequest {
    @Property(name = "uri")
    public String uri;
}
