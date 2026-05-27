package ai.core.api.server.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class McpServerStatusResponse {
    @Property(name = "server_id")
    public String serverId;

    @Property(name = "state")
    public String state;

    @Property(name = "message")
    public String message;
}
