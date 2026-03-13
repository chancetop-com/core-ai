package ai.core.mcp.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Global registry for McpClientManager.
 * Used by AgentBuilder to load MCP tools by server name.
 *
 * @author stephen
 */
public final class McpClientManagerRegistry {
    private static McpClientManager manager;
    private static final List<Consumer<McpClientManager>> creationListeners = new CopyOnWriteArrayList<>();

    public static void setManager(McpClientManager manager) {
        McpClientManagerRegistry.manager = manager;
    }

    public static McpClientManager getManager() {
        return manager;
    }

    public static void addCreationListener(Consumer<McpClientManager> listener) {
        creationListeners.add(listener);
    }

    public static void notifyCreation(McpClientManager manager) {
        for (var listener : creationListeners) {
            listener.accept(manager);
        }
    }

    public static void clear() {
        manager = null;
    }

    private McpClientManagerRegistry() {
    }
}
