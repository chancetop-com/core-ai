package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * A structured reference to a tool, replacing the plain string toolId.
 * Contains the tool identifier, its source type, and an optional source name
 * for disambiguation (e.g., which MCP server, which API app).
 *
 * @author stephen
 */
public class ToolRef {
    public static ToolRef fromLegacyToolId(String toolId) {
        if (toolId == null) return null;

        if (toolId.startsWith("builtin-")) return new ToolRef(toolId, ToolSourceType.BUILTIN);
        if (toolId.startsWith("config:")) return new ToolRef(toolId, ToolSourceType.MCP, toolId.substring("config:".length()));
        if (toolId.startsWith("api-app:") || "builtin-service-api".equals(toolId)) return new ToolRef(toolId, ToolSourceType.API);

        return new ToolRef(toolId, null);
    }

    @Field(name = "id")
    public String id;

    @Field(name = "type")
    public ToolSourceType type;

    /** Source identifier for disambiguation. E.g., MCP server name, API app name. */
    @Field(name = "source")
    public String source;

    public ToolRef() {
    }

    public ToolRef(String id, ToolSourceType type) {
        this.id = id;
        this.type = type;
    }

    public ToolRef(String id, ToolSourceType type, String source) {
        this.id = id;
        this.type = type;
        this.source = source;
    }

    public String toLegacyToolId() {
        return id;
    }
}
