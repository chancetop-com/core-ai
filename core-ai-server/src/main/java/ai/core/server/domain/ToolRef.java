package ai.core.server.domain;

import core.framework.mongo.Field;

import java.util.Objects;

/**
 * @author stephen
 */
public class ToolRef {
    private static final String MCP_TOOL_PREFIX = "mcp-tool:";

    /**
     * Parse an individual MCP tool ref id of the form "mcp-tool:{serverId}:{toolName}",
     * or "mcp-tool:{toolName}" with the serverId supplied via the source field.
     * The serverId may itself contain colons (config-file servers use "config:{name}"),
     * so split on the LAST colon to keep the server prefix intact.
     * Returns null when the id is not an individual MCP tool ref.
     */
    public static McpToolId parseMcpToolId(String id, String source) {
        if (id == null || !id.startsWith(MCP_TOOL_PREFIX)) return null;
        var remaining = id.substring(MCP_TOOL_PREFIX.length());
        var colonIdx = remaining.lastIndexOf(':');
        if (colonIdx > 0) {
            return new McpToolId(remaining.substring(0, colonIdx), remaining.substring(colonIdx + 1));
        }
        return new McpToolId(source, remaining);
    }

    public static ToolRef fromLegacyToolId(String toolId) {
        if (toolId == null) return null;

        var ref = new ToolRef();
        ref.id = toolId;
        ref.inferTypeFromId();
        return ref;
    }

    public static ToolRef of(String id, ToolSourceType type) {
        var ref = new ToolRef();
        ref.id = id;
        ref.type = type;
        return ref;
    }

    public static ToolRef of(String id, ToolSourceType type, String source) {
        var ref = new ToolRef();
        ref.id = id;
        ref.type = type;
        ref.source = source;
        return ref;
    }

    @Field(name = "id")
    public String id;

    @Field(name = "type")
    public ToolSourceType type;

    @Field(name = "source")
    public String source;

    public void inferTypeFromId() {
        if (id == null) return;
        if ("builtin-service-api".equals(id)
                || id.startsWith("api-app:")
                || id.startsWith("api-service:")
                || id.startsWith("api-operation:")) {
            type = ToolSourceType.API;
        } else if (id.startsWith("builtin-")) {
            type = ToolSourceType.BUILTIN;
        } else if (id.startsWith("mcp-tool:")) {
            type = ToolSourceType.MCP;
        } else if (id.startsWith("config:")) {
            type = ToolSourceType.MCP;
            source = id.substring("config:".length());
        }
    }

    public String toLegacyToolId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolRef that)) return false;
        return Objects.equals(id, that.id) && type == that.type && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, source);
    }

    @Override
    public String toString() {
        return "ToolRef{id=" + id + ", type=" + type + ", source=" + source + "}";
    }

    /** Parsed serverId and toolName of an individual MCP tool ref. */
    public record McpToolId(String serverId, String toolName) {
    }
}
