package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.tool.mcp.McpToolProvider;
import ai.core.tool.registry.ToolProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides MCP tools for a specific server, optionally filtered to named tools.
 * <p>
 * Supports two refresh strategies:
 * <ul>
 *   <li>{@link RefreshPolicy#EVERY_TURN} — for global (non-sandbox) MCP servers that may change at runtime</li>
 *   <li>{@link RefreshPolicy#MANUAL} — for sandbox-hosted MCP, where the session manager is stable
 *       after initial registration; call {@link ai.core.tool.registry.ToolRegistry#invalidateCache(String)}
 *       when the sandbox session changes</li>
 * </ul>
 *
 * @author Lim Chen
 */
public class McpToolSetProvider implements ToolProvider {
    private final String id;
    private final McpClientManager manager;
    private final String serverId;
    private final List<String> includes;
    private final RefreshPolicy refreshPolicy;

    /**
     * @param serverId   the MCP server id known to the {@link McpClientManager}
     * @param manager    session-scoped manager, or {@code null} to use the global manager
     * @param includes   tool name patterns to include, or {@code null} for all tools
     * @param sandbox    {@code true} to use {@code MANUAL} refresh, {@code false} for {@code EVERY_TURN}
     */
    public McpToolSetProvider(String serverId, McpClientManager manager, List<String> includes, boolean sandbox) {
        this.id = McpToolProvider.MCP + ":" + serverId;
        this.serverId = serverId;
        this.manager = manager;
        this.includes = includes;
        this.refreshPolicy = sandbox ? RefreshPolicy.MANUAL : RefreshPolicy.EVERY_TURN;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public RefreshPolicy refreshPolicy() {
        return refreshPolicy;
    }

    @Override
    public Map<String, ToolCall> provide() {
        var mgr = manager != null ? manager : McpClientManagerRegistry.getManager();
        if (mgr == null || !mgr.hasServer(serverId)) return Map.of();
        var tools = McpToolCalls.from(mgr, List.of(serverId), includes);
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : tools) {
            map.put(tc.getName(), tc);
        }
        return map;
    }
}
