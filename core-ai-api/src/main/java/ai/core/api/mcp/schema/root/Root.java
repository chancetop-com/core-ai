package ai.core.api.mcp.schema.root;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class Root {
    @Property(name = "uri")
    public String uri;

    @Property(name = "name")
    public String name;
}
