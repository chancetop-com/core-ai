package ai.core.api.mcp.schema;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class ClientCapabilities {
    @Property(name = "experimental")
    public Map<String, String> experimental;

    @Property(name = "roots")
    public RootCapabilities roots;

    @Property(name = "sampling")
    public Sampling sampling;
}
