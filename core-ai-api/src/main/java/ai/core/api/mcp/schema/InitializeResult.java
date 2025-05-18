package ai.core.api.mcp.schema;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class InitializeResult {
    @Property(name = "protocolVersion")
    public String protocolVersion;

    @Property(name = "capabilities")
    public ServerCapabilities capabilities;

    @Property(name = "serverInfo")
    public Implementation serverInfo;

    @Property(name = "instructions")
    public String instructions;
}
