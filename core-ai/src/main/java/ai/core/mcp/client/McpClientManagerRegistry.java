package ai.core.mcp.client;

/**
 * Global registry for McpClientManager.
 * Used by AgentBuilder to load MCP tools by server name.
 *
 * @author stephen
 */
public final class McpClientManagerRegistry {
    private static McpClientManager manager;

    public static void setManager(McpClientManager manager) {
        McpClientManagerRegistry.manager = manager;
    }

    public static McpClientManager getManager() {
        return manager;
    }

    public static void clear() {
        manager = null;
    }

    private McpClientManagerRegistry() {
    }
}
