package ai.core.api.server.tool;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class McpServerToolsResponse {
    @Property(name = "server_id")
    public String serverId;

    @Property(name = "server_name")
    public String serverName;

    @Property(name = "tools")
    public List<McpToolInfo> tools;

    public static class McpToolInfo {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;
    }
}
