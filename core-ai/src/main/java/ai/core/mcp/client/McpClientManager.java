package ai.core.mcp.client;

import ai.core.api.mcp.schema.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author stephen
 */
public class McpClientManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpClientManager.class);

    @SuppressWarnings("unchecked")
    public static McpClientManager fromConfig(Map<String, Object> mcpServersConfig) {
        var manager = new McpClientManager();
        for (var entry : mcpServersConfig.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> serverConfig) {
                var config = McpServerConfig.fromMap(entry.getKey(), (Map<String, Object>) serverConfig);
                manager.addServer(config);
            }
        }
        manager.registerShutdownHook();
        return manager;
    }

    /**
     * Create with a list of server configurations.
     */
    public static McpClientManager of(List<McpServerConfig> configs) {
        var manager = new McpClientManager();
        for (var config : configs) {
            manager.addServer(config);
        }
        manager.registerShutdownHook();
        return manager;
    }

    /**
     * Create with varargs server configurations.
     */
    public static McpClientManager of(McpServerConfig... configs) {
        return of(List.of(configs));
    }

    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, McpClientService> clients = new ConcurrentHashMap<>();
    private final Map<String, ConnectionState> states = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final List<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();
    private Thread shutdownHook;
    private volatile boolean closed = false;

    /**
     * Add a server configuration.
     */
    public void addServer(McpServerConfig config) {
        String name = config.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Server config must have a name");
        }
        configs.put(name, config);
        states.put(name, ConnectionState.NOT_CONNECTED);
        locks.put(name, new Object());
        LOGGER.info("Added MCP server config: {}", name);
    }

    /**
     * Remove a server and close its client if exists.
     */
    public void removeServer(String serverName) {
        closeClient(serverName);
        configs.remove(serverName);
        states.remove(serverName);
        locks.remove(serverName);
        LOGGER.info("Removed MCP server: {}", serverName);
    }

    /**
     * Get all configured server names.
     */
    public Set<String> getServerNames() {
        return Set.copyOf(configs.keySet());
    }

    /**
     * Check if a server is configured.
     */
    public boolean hasServer(String serverName) {
        return configs.containsKey(serverName);
    }

    /**
     * Get client for the specified server (lazy initialization, thread-safe).
     * Creates client if not exists, reuses if already created.
     *
     * @throws IllegalArgumentException if server is not configured
     * @throws McpClientException       if client creation fails
     */
    public McpClientService getClient(String serverName) {
        var config = configs.get(serverName);
        if (config == null) {
            throw new IllegalArgumentException("Server not configured: " + serverName);
        }

        var existingClient = clients.get(serverName);
        if (existingClient != null) {
            return existingClient;
        }

        // Double-checked locking for thread-safe lazy initialization
        var lock = locks.get(serverName);
        synchronized (lock) {
            existingClient = clients.get(serverName);
            return Objects.requireNonNullElseGet(existingClient, () -> createClient(serverName, config));

        }
    }

    /**
     * Get client if it has been created, without triggering initialization.
     */
    public Optional<McpClientService> getClientIfPresent(String serverName) {
        return Optional.ofNullable(clients.get(serverName));
    }

    /**
     * Get connection state for a server.
     */
    public ConnectionState getState(String serverName) {
        return states.getOrDefault(serverName, ConnectionState.NOT_CONNECTED);
    }

    /**
     * Get all connection states.
     */
    public Map<String, ConnectionState> getAllStates() {
        return Map.copyOf(states);
    }

    /**
     * Warmup all configured clients (parallel initialization).
     * Useful for pre-connecting at application startup.
     */
    public void warmup() {
        LOGGER.info("Warming up {} MCP clients...", configs.size());
        configs.keySet().parallelStream().forEach(serverName -> {
            try {
                getClient(serverName);
            } catch (Exception e) {
                LOGGER.error("Failed to warmup client: {}", serverName, e);
            }
        });
        LOGGER.info("MCP clients warmup completed");
    }

    /**
     * Get all initialized clients.
     */
    public Map<String, McpClientService> getAllClients() {
        return Map.copyOf(clients);
    }

    /**
     * List tools from all connected servers.
     *
     * @return Map of serverName to tools list
     */
    public Map<String, List<Tool>> listAllTools() {
        var result = new HashMap<String, List<Tool>>();
        for (var entry : clients.entrySet()) {
            try {
                result.put(entry.getKey(), entry.getValue().listTools());
            } catch (Exception e) {
                LOGGER.error("Failed to list tools from server: {}", entry.getKey(), e);
                result.put(entry.getKey(), List.of());
            }
        }
        return result;
    }

    /**
     * List all tools flattened with server name prefix.
     * Tool name format: serverName/originalToolName
     */
    public List<Tool> listAllToolsFlattened() {
        var result = new ArrayList<Tool>();
        for (var entry : clients.entrySet()) {
            String serverName = entry.getKey();
            try {
                var tools = entry.getValue().listTools();
                for (var tool : tools) {
                    var prefixedTool = new Tool();
                    prefixedTool.name = serverName + "/" + tool.name;
                    prefixedTool.description = "[" + serverName + "] " + tool.description;
                    prefixedTool.inputSchema = tool.inputSchema;
                    result.add(prefixedTool);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to list tools from server: {}", serverName, e);
            }
        }
        return result;
    }

    /**
     * Add a connection state listener.
     */
    public void addListener(ConnectionStateListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a connection state listener.
     */
    public void removeListener(ConnectionStateListener listener) {
        listeners.remove(listener);
    }

    /**
     * Close client for the specified server.
     */
    public void closeClient(String serverName) {
        var client = clients.remove(serverName);
        if (client != null) {
            try {
                client.close();
                updateState(serverName, ConnectionState.DISCONNECTED);
                LOGGER.info("Closed MCP client: {}", serverName);
            } catch (Exception e) {
                updateState(serverName, ConnectionState.FAILED);
                LOGGER.error("Error closing MCP client: {}", serverName, e);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        LOGGER.info("Closing McpClientManager with {} clients...", clients.size());
        for (String serverName : List.copyOf(clients.keySet())) {
            closeClient(serverName);
        }
        listeners.clear();
        removeShutdownHook();
        // Dispose Reactor schedulers used by MCP SDK
        disposeReactorSchedulers();
        LOGGER.info("McpClientManager closed");
    }

    private void disposeReactorSchedulers() {
        try {
            reactor.core.scheduler.Schedulers.shutdownNow();
            LOGGER.debug("Reactor schedulers shut down");
        } catch (Exception e) {
            LOGGER.warn("Error shutting down Reactor schedulers: {}", e.getMessage());
        }
    }

    /**
     * Register a JVM shutdown hook to ensure cleanup on abnormal exit.
     */
    void registerShutdownHook() {
        shutdownHook = new Thread(() -> {
            if (!closed) {
                LOGGER.info("JVM shutdown hook triggered, closing McpClientManager...");
                close();
            }
        }, "mcp-client-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM is already shutting down, ignore
                LOGGER.warn("Failed to remove shutdown hook, JVM is shutting down");
            }
            shutdownHook = null;
        }
    }

    private McpClientService createClient(String serverName, McpServerConfig config) {
        updateState(serverName, ConnectionState.CONNECTING);
        try {
            var client = new McpClientService(config);
            clients.put(serverName, client);
            updateState(serverName, ConnectionState.CONNECTED);
            LOGGER.info("Created MCP client: {}, transport: {}", serverName, config.getTransportType());
            return client;
        } catch (Exception e) {
            updateState(serverName, ConnectionState.FAILED);
            throw new McpClientException("Failed to create client for server: " + serverName, e);
        }
    }

    private void updateState(String serverName, ConnectionState newState) {
        var oldState = states.put(serverName, newState);
        if (oldState != newState) {
            notifyListeners(serverName, oldState, newState);
        }
    }

    private void notifyListeners(String serverName, ConnectionState oldState, ConnectionState newState) {
        for (var listener : listeners) {
            try {
                listener.onStateChanged(serverName, oldState, newState);
            } catch (Exception e) {
                LOGGER.error("Error notifying listener for server: {}", serverName, e);
            }
        }
    }

    /**
     * Connection state of an MCP client.
     */
    public enum ConnectionState {
        NOT_CONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        FAILED
    }

    /**
     * Listener for connection state changes.
     */
    @FunctionalInterface
    public interface ConnectionStateListener {
        void onStateChanged(String serverName, ConnectionState oldState, ConnectionState newState);
    }

    /**
     * Exception thrown when MCP client operations fail.
     */
    public static class McpClientException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = -1002912289095930440L;

        public McpClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
