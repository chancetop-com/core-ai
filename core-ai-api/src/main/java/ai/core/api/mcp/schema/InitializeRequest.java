package ai.core.api.mcp.schema;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class InitializeRequest {
    @Property(name = "protocolVersion")
    public String protocolVersion;

    @Property(name = "capabilities")
    public ClientCapabilities capabilities;

    @Property(name = "clientInfo")
    public Implementation clientInfo;
}
