package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

/**
 * Tool detail with the JSON schema of its arguments.
 * <p>
 * {@code inputSchema} is the JSON text of the MCP input schema (arbitrary nested object —
 * core-ng view beans only allow concrete value types, so dynamic JSON travels as text).
 *
 * @author stephen
 */
public class HubToolDetail {
    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "ref_id")
    public String refId;

    @Property(name = "server")
    public String server;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "input_schema")
    public String inputSchema;

    @Property(name = "server_state")
    public String serverState;
}
