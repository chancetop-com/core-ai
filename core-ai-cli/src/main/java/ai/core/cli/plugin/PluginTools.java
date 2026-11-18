package ai.core.cli.plugin;

import ai.core.agent.ExecutionContext;
import ai.core.cli.appserver.LocalArtifacts;
import ai.core.cli.appserver.LocalMemory;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Read-only host tools contributed by active plugins. The desktop sends descriptors through
 * CORE_AI_PLUGIN_TOOLS; this class validates them and executes only two built-in read operations.
 * No plugin code is loaded or invoked.
 *
 * @author stephen
 */
public final class PluginTools {
    public static final String ENV = "CORE_AI_PLUGIN_TOOLS";

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> OPERATIONS = Set.of("artifacts.list", "memory.list");
    private static final int MAX_TOOLS = 20;

    public static List<ToolCall> fromEnvironment() {
        if (Boolean.getBoolean("core.ai.workspace.restricted")) return List.of();
        return parse(System.getenv(ENV));
    }

    static List<ToolCall> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        var tools = new ArrayList<ToolCall>();
        try {
            var node = MAPPER.readTree(json);
            if (!node.isArray()) return List.of();
            for (var entry : node) {
                if (tools.size() >= MAX_TOOLS) break;
                if (!entry.isObject()) continue;
                var name = entry.path("name").asText("");
                var description = entry.path("description").asText("");
                var operation = entry.path("operation").asText("");
                var limit = Math.clamp(entry.path("limit").asInt(50), 1, 200);
                if (!name.matches("^[a-z][a-z0-9_]{1,63}$") || description.isBlank()
                        || description.length() > 1000 || !OPERATIONS.contains(operation)) {
                    LOGGER.warn("ignoring invalid plugin tool descriptor: {}", name);
                    continue;
                }
                tools.add(HostReadTool.create(name, description, operation, limit));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ignoring invalid plugin tools payload: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(tools);
    }

    private PluginTools() {
    }

    private static final class HostReadTool extends ToolCall {
        static HostReadTool create(String name, String description, String operation, int limit) {
            return new Builder(name, description, operation, limit).build();
        }

        private final String operation;
        private final int limit;

        private HostReadTool(String operation, int limit) {
            this.operation = operation;
            this.limit = limit;
        }

        @Override
        public ToolCallResult execute(String arguments) {
            return ToolCallResult.failed(getName() + " requires execution context");
        }

        @Override
        public ToolCallResult execute(String arguments, ExecutionContext context) {
            var workspace = context == null ? null : context.getCustomVariables().get("workspace");
            if (!(workspace instanceof String path) || path.isBlank()) {
                return ToolCallResult.failed(getName() + " requires a workspace");
            }
            try {
                var params = JsonUtil.OBJECT_MAPPER.createObjectNode();
                params.put("limit", limit);
                var result = switch (operation) {
                    case "artifacts.list" -> LocalArtifacts.list(Path.of(path), params);
                    case "memory.list" -> LocalMemory.list(Path.of(path), params);
                    default -> throw new IllegalStateException("unsupported operation: " + operation);
                };
                return ToolCallResult.completed(JsonUtil.toJson(result));
            } catch (RuntimeException e) {
                return ToolCallResult.failed(getName() + " failed: " + e.getMessage(), e);
            }
        }

        private static final class Builder extends ToolCall.Builder<Builder, HostReadTool> {
            private final String toolName;
            private final String toolDescription;
            private final String operation;
            private final int limit;

            private Builder(String toolName, String toolDescription, String operation, int limit) {
                this.toolName = toolName;
                this.toolDescription = toolDescription;
                this.operation = operation;
                this.limit = limit;
            }

            @Override
            protected Builder self() {
                return this;
            }

            private HostReadTool build() {
                this.name(toolName);
                this.description(toolDescription);
                var tool = new HostReadTool(operation, limit);
                build(tool);
                return tool;
            }
        }
    }
}
