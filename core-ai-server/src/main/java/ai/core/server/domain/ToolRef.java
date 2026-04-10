package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * @author stephen
 */
public class ToolRef {
    public static ToolRef fromLegacyToolId(String toolId) {
        if (toolId == null) return null;

        var ref = new ToolRef();
        ref.id = toolId;

        if (toolId.startsWith("builtin-")) {
            ref.type = ToolSourceType.BUILTIN;
        } else if (toolId.startsWith("config:")) {
            ref.type = ToolSourceType.MCP;
            ref.source = toolId.substring("config:".length());
        } else if (toolId.startsWith("api-app:") || "builtin-service-api".equals(toolId)) {
            ref.type = ToolSourceType.API;
        }

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

    public String toLegacyToolId() {
        return id;
    }
}
