package ai.core.tool.mcp;

import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides MCP tools dynamically from {@link McpClientManagerRegistry}.
 * Tools are refreshed on every {@link #provide()} call, so newly connected
 * MCP servers are automatically picked up.
 *
 * @author Lim Chen
 */
public class McpToolProvider implements ToolProvider {
    public static final String MCP = "mcp";

    @Override
    public String id() {
        return MCP;
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public Map<String, ToolCall> provide() {
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) return Map.of();
        var serverNames = manager.getServerNames();
        if (serverNames == null || serverNames.isEmpty()) return Map.of();
        var tools = McpToolCalls.from(manager, new ArrayList<>(serverNames), null);
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : tools) {
            map.put(tc.getName(), tc);
        }
        return map;
    }
}
