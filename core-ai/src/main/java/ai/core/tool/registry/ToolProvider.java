package ai.core.tool.registry;

import ai.core.tool.ToolCall;

import java.util.Map;

/**
 * A source of tools. Each implementation represents one origin —
 * built-in constants, MCP servers, API definitions, plugins, etc.
 * <p>
 * Providers are registered with the {@link ToolRegistry} in priority order.
 * When two providers supply a tool with the same name, the higher-priority
 * (lower number) provider's tool takes precedence.
 *
 * @author Lim Chen
 */
public interface ToolProvider {
    String id();

    default int priority() {
        return 100;
    }

    Map<String, ToolCall> provide();
}
