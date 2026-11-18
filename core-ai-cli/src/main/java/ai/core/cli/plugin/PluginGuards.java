package ai.core.cli.plugin;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.session.ToolCallDeniedException;
import ai.core.llm.domain.FunctionCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies static, data-only tool guards contributed by trusted plugins. The desktop sends guard
 * descriptors through CORE_AI_PLUGIN_GUARDS; no plugin code runs in the engine.
 *
 * @author stephen
 */
public final class PluginGuards {
    public static final String ENV = "CORE_AI_PLUGIN_GUARDS";

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginGuards.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_GUARDS = 20;
    private static final int MAX_TOOL_NAMES = 200;

    public static AbstractLifecycle fromEnvironment() {
        if (Boolean.getBoolean("core.ai.workspace.restricted")) return null;
        var guards = parse(System.getenv(ENV));
        return guards.isEmpty() ? null : new Lifecycle(guards);
    }

    static List<Guard> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        var guards = new ArrayList<Guard>();
        var seen = new LinkedHashSet<String>();
        try {
            var node = MAPPER.readTree(json);
            if (!node.isArray()) return List.of();
            for (var entry : node) {
                if (guards.size() >= MAX_GUARDS || seen.size() >= MAX_TOOL_NAMES) break;
                if (!entry.isObject()) continue;
                var name = entry.path("name").asText("");
                var description = entry.path("description").asText("");
                if (!name.matches("^[a-z][a-z0-9_.:-]{1,159}$") || description.isBlank() || description.length() > 1000) {
                    LOGGER.warn("ignoring invalid plugin guard descriptor: {}", name);
                    continue;
                }
                var toolNames = new LinkedHashSet<String>();
                for (var tool : entry.path("toolNames")) {
                    var toolName = tool.asText("");
                    if (!toolName.matches("^[a-z][a-z0-9_.-]{0,127}$")) continue;
                    if (seen.size() >= MAX_TOOL_NAMES) break;
                    if (seen.add(toolName)) toolNames.add(toolName);
                }
                if (!toolNames.isEmpty()) guards.add(new Guard(name, description, Set.copyOf(toolNames)));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ignoring invalid plugin guards payload: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(guards);
    }

    static boolean denies(List<Guard> guards, String toolName) {
        for (var guard : guards) {
            if (guard.toolNames().contains(toolName)) return true;
        }
        return false;
    }

    private PluginGuards() {
    }

    record Guard(String name, String description, Set<String> toolNames) {
    }

    private static final class Lifecycle extends AbstractLifecycle {
        private final List<Guard> guards;

        private Lifecycle(List<Guard> guards) {
            this.guards = guards;
        }

        @Override
        public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
            if (functionCall == null || functionCall.function == null) return;
            var toolName = functionCall.function.name;
            if (denies(guards, toolName)) {
                LOGGER.info("plugin guard denied tool: {}", toolName);
                throw new ToolCallDeniedException(toolName);
            }
        }
    }
}
