package ai.core.tool.mcp;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides MCP tools from {@link McpClientManagerRegistry}.
 * <p>
 * The no-arg constructor enumerates all servers (CLI usage, {@link RefreshPolicy#EVERY_TURN}).
 * The parameterized constructor targets a specific server with optional tool filtering
 * (Server usage, with configurable {@link RefreshPolicy}).
 *
 * @author Lim Chen
 */
public class McpToolProvider implements ToolProvider {
    public static final String MCP = "mcp";

    private final String id;
    private final McpClientManager manager;
    private final String lookupKey;
    private final String label;
    private final List<String> includes;
    private final RefreshPolicy refreshPolicy;

    public McpToolProvider() {
        this.id = MCP;
        this.lookupKey = null;
        this.label = null;
        this.manager = null;
        this.includes = null;
        this.refreshPolicy = RefreshPolicy.EVERY_TURN;
    }

    public McpToolProvider(String lookupKey, McpClientManager manager, List<String> includes, RefreshPolicy refreshPolicy) {
        this(MCP + ":" + lookupKey, lookupKey, lookupKey, manager, includes, refreshPolicy);
    }

    public McpToolProvider(String id, String lookupKey, McpClientManager manager, List<String> includes, RefreshPolicy refreshPolicy) {
        this(id, lookupKey, lookupKey, manager, includes, refreshPolicy);
    }

    public McpToolProvider(String id, String lookupKey, String label, McpClientManager manager, List<String> includes, RefreshPolicy refreshPolicy) {
        this.id = id;
        this.lookupKey = lookupKey;
        this.label = label;
        this.manager = manager;
        this.includes = includes;
        this.refreshPolicy = refreshPolicy;
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
        if (mgr == null) return Map.of();
        List<String> servers;
        if (lookupKey != null) {
            if (!mgr.hasServer(lookupKey)) return Map.of();
            servers = List.of(lookupKey);
        } else {
            var names = mgr.getServerNames();
            if (names == null || names.isEmpty()) return Map.of();
            servers = new ArrayList<>(names);
        }
        var tools = McpToolCalls.from(mgr, servers, includes, null, label);
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : tools) {
            map.put(tc.getName(), tc);
        }
        return map;
    }
}
