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
 * <p>
 * Each provider declares a {@link RefreshPolicy} that controls how often
 * {@link #provide()} is called:
 * <ul>
 *   <li>{@link RefreshPolicy#EVERY_TURN EVERY_TURN} — call every time tools are materialized (default)</li>
 *   <li>{@link RefreshPolicy#ONCE ONCE} — call once, cache forever</li>
 *   <li>{@link RefreshPolicy#MANUAL MANUAL} — cache until explicitly invalidated</li>
 * </ul>
 *
 * @author Lim Chen
 */
public interface ToolProvider {
    String BUILTIN_PLANNING = "builtin-planning";
    String BUILTIN_FILES = "builtin-files";
    String BUILTIN_MULTIMODAL = "builtin-multimodal";
    String BUILTIN_WEB = "builtin-web";
    String BUILTIN_BASH = "builtin-bash";

    String BUILTIN = "builtin";
    String USER = "user-provided";

    String id();

    default int priority() {
        return 100;
    }

    Map<String, ToolCall> provide();

    /**
     * Controls caching behaviour during {@link ToolRegistry#materialize()}.
     * <ul>
     *   <li>{@code EVERY_TURN} — {@code provide()} is invoked on every materialization.</li>
     *   <li>{@code ONCE} — the result of the first {@code provide()} call is cached indefinitely.</li>
     *   <li>{@code MANUAL} — cached until {@link ToolRegistry#invalidateCache(String)} is called.</li>
     * </ul>
     */
    enum RefreshPolicy {
        EVERY_TURN,
        ONCE,
        MANUAL
    }

    default RefreshPolicy refreshPolicy() {
        return RefreshPolicy.EVERY_TURN;
    }
}
