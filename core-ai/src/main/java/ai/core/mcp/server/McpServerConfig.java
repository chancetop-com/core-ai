package ai.core.mcp.server;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class McpServerConfig {
    @Property(name = "servers")
    public List<CmdMcpServer> servers;

    public enum McpServerType {
        DOCKER,
        UV
    }

    public static class CmdMcpServer {
        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "type")
        public McpServerType type;

        @Property(name = "args")
        public List<String> args;
    }
}
