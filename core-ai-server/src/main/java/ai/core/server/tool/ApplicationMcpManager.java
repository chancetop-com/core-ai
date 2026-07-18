package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;

/**
 * Owns the application-scoped MCP manager without affecting session-scoped managers.
 *
 * @author Stephen
 */
public class ApplicationMcpManager {
    private final Object lock = new Object();
    private McpClientManager manager;

    public McpClientManager get() {
        synchronized (lock) {
            return manager;
        }
    }

    public McpClientManager getOrCreate() {
        synchronized (lock) {
            if (manager == null) {
                manager = new McpClientManager();
            }
            return manager;
        }
    }

    public void set(McpClientManager manager) {
        synchronized (lock) {
            this.manager = manager;
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if (manager == null) return;
            manager.close();
            manager = null;
        }
    }

    boolean isInitialized() {
        synchronized (lock) {
            return manager != null;
        }
    }
}
