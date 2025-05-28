package ai.core.api.mcp.schema.tool;

import ai.core.api.jsonschema.JsonSchema;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class Tool {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "need_auth")
    public Boolean needAuth;

    @Property(name = "inputSchema")
    public JsonSchema inputSchema;
}
