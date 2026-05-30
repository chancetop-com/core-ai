package ai.core.server.domain;

import core.framework.mongo.Field;

import java.util.Objects;

/**
 * @author stephen
 */
public class ToolRef {
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
        if (id.startsWith("builtin-")) {
            type = ToolSourceType.BUILTIN;
        } else if (id.startsWith("mcp-tool:")) {
            type = ToolSourceType.MCP;
        } else if (id.startsWith("config:")) {
            type = ToolSourceType.MCP;
            source = id.substring("config:".length());
        } else if (id.startsWith("api-app:") || "builtin-service-api".equals(id)) {
            type = ToolSourceType.API;
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
}
