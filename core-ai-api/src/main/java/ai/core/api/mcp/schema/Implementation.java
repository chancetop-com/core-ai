package ai.core.api.mcp.schema;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class Implementation {
    public static Implementation of(String name, String version) {
        var rsp = new Implementation();
        rsp.name = name;
        rsp.version = version;
        return rsp;
    }

    @Property(name = "name")
    public String name;

    @Property(name = "version")
    public String version;
}
