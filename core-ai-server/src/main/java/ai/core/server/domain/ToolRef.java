package ai.core.server.domain;

import core.framework.mongo.Field;

import java.util.Objects;

/**
 * @author stephen
 */
public class ToolRef {
    private static final String MCP_TOOL_PREFIX = "mcp-tool:";
    private static final String BUILTIN_PREFIX = "builtin:";

    /** Prefix of tool refs that wrap an LLM_CALL agent definition as a tool, e.g. "llm-call:{definitionId}". */
    public static final String LLM_CALL_PREFIX = "llm-call:";

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

    /**
     * Parse an individual builtin group tool ref id of the form
     * "builtin:{groupId}:{toolName}", e.g. "builtin:self-harness:list_agents".
     * The group id may itself contain colons, so split on the LAST colon.
     * Returns null when the id is not an individual builtin group tool ref.
     */
    public static BuiltinGroupToolId parseBuiltinGroupToolId(String id) {
        if (id == null || !id.startsWith(BUILTIN_PREFIX)) return null;
        var remaining = id.substring(BUILTIN_PREFIX.length());
        var colonIdx = remaining.lastIndexOf(':');
        if (colonIdx <= 0 || colonIdx == remaining.length() - 1) return null;
        return new BuiltinGroupToolId(BUILTIN_PREFIX + remaining.substring(0, colonIdx), remaining.substring(colonIdx + 1));
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
        } else if (id.startsWith("builtin-") || id.startsWith("builtin:")) {
            type = ToolSourceType.BUILTIN;
        } else if (id.startsWith("mcp-tool:")) {
            type = ToolSourceType.MCP;
        } else if (id.startsWith("config:")) {
            type = ToolSourceType.MCP;
            source = id.substring("config:".length());
        } else if (id.startsWith(LLM_CALL_PREFIX)) {
            type = ToolSourceType.LLM_CALL;
        }
    }

    public String toLegacyToolId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolRef that)) return false;
        return type == that.type && Objects.equals(id, that.id) && Objects.equals(source, that.source);
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

    /** Parsed groupId and toolName of an individual builtin group tool ref. */
    public record BuiltinGroupToolId(String groupId, String toolName) {
    }
}
