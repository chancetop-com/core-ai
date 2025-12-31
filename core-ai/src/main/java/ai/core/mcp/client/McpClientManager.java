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

    public static McpClientManager of(List<McpServerConfig> configs) {
        var manager = new McpClientManager();
        for (var config : configs) {
            manager.addServer(config);
        }
        manager.registerShutdownHook();
        return manager;
    }

    public static McpClientManager of(McpServerConfig... configs) {
        return of(List.of(configs));
    }

    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, McpClientService> clients = new ConcurrentHashMap<>();
    private final Map<String, ConnectionState> states = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final List<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();
    private final Object connectionMonitorLock = new Object();
    private McpConnectionMonitor connectionMonitor;
    private volatile boolean connectionMonitorInitialized = false;
    private Thread shutdownHook;
    private volatile boolean closed = false;

    private McpConnectionMonitor getConnectionMonitor() {
        if (!connectionMonitorInitialized) {
            synchronized (connectionMonitorLock) {
                if (!connectionMonitorInitialized) {
                    this.connectionMonitor = new McpConnectionMonitor(
                        () -> configs,
                        () -> clients,
                        this::getState,
                        this::handleDisconnection,
                        new ManagerReconnectCallback()
                    );
                    connectionMonitorInitialized = true;
                }
            }
        }
        return connectionMonitor;
    }

    public void addServer(McpServerConfig config) {
        String name = config.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Server config must have a name");
        }
        configs.put(name, config);
        states.put(name, ConnectionState.NOT_CONNECTED);
        locks.put(name, new Object());
        getConnectionMonitor().addServer(name);
        LOGGER.info("Added MCP server config: {}", name);
    }

    public void removeServer(String serverName) {
        getConnectionMonitor().removeServer(serverName);
        closeClient(serverName);
        configs.remove(serverName);
        states.remove(serverName);
        locks.remove(serverName);
        LOGGER.info("Removed MCP server: {}", serverName);
    }

    public Set<String> getServerNames() {
        return Set.copyOf(configs.keySet());
    }

    public boolean hasServer(String serverName) {
        return configs.containsKey(serverName);
    }

    public McpClientService getClient(String serverName) {
        var config = configs.get(serverName);
        if (config == null) {
            throw new IllegalArgumentException("Server not configured: " + serverName);
        }

        var existingClient = clients.get(serverName);
        if (existingClient != null) {
            return existingClient;
        }

        var lock = locks.get(serverName);
        synchronized (lock) {
            existingClient = clients.get(serverName);
            return Objects.requireNonNullElseGet(existingClient, () -> createClient(serverName, config));
        }
    }

    public Optional<McpClientService> getClientIfPresent(String serverName) {
        return Optional.ofNullable(clients.get(serverName));
    }

    public ConnectionState getState(String serverName) {
        return states.getOrDefault(serverName, ConnectionState.NOT_CONNECTED);
    }

    public Map<String, ConnectionState> getAllStates() {
        return Map.copyOf(states);
    }

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

        boolean anyHeartbeatEnabled = configs.values().stream()
            .anyMatch(McpServerConfig::isEnableHeartbeat);
        if (anyHeartbeatEnabled) {
            startHeartbeat();
        }
    }

    public void startHeartbeat() {
        getConnectionMonitor().startHeartbeat();
    }

    public void stopHeartbeat() {
        getConnectionMonitor().stopHeartbeat();
    }

    public void reconnect(String serverName) {
        if (!configs.containsKey(serverName)) {
            throw new IllegalArgumentException("Server not configured: " + serverName);
        }
        LOGGER.info("Manual reconnect requested for server: {}", serverName);
        getConnectionMonitor().resetReconnectAttempts(serverName);
        handleDisconnection(serverName);
        var config = configs.get(serverName);
        if (config != null && !config.isAutoReconnect()) {
            getConnectionMonitor().scheduleReconnect(serverName);
        }
    }

    public boolean isReconnecting(String serverName) {
        return states.get(serverName) == ConnectionState.RECONNECTING;
    }

    public Map<String, McpClientService> getAllClients() {
        return Map.copyOf(clients);
    }

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

    public void addListener(ConnectionStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ConnectionStateListener listener) {
        listeners.remove(listener);
    }

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
        getConnectionMonitor().close();
        for (String serverName : List.copyOf(clients.keySet())) {
            closeClient(serverName);
        }
        listeners.clear();
        removeShutdownHook();
        disposeReactorSchedulers();
        LOGGER.info("McpClientManager closed");
    }

    private void handleDisconnection(String serverName) {
        var client = clients.remove(serverName);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing disconnected client: {}", serverName, e);
            }
        }
        updateState(serverName, ConnectionState.DISCONNECTED);
    }

    private void disposeReactorSchedulers() {
        try {
            reactor.core.scheduler.Schedulers.shutdownNow();
            LOGGER.debug("Reactor schedulers shut down");
        } catch (Exception e) {
            LOGGER.warn("Error shutting down Reactor schedulers: {}", e.getMessage());
        }
    }

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

    public enum ConnectionState {
        NOT_CONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
        FAILED
    }

    @FunctionalInterface
    public interface ConnectionStateListener {
        void onStateChanged(String serverName, ConnectionState oldState, ConnectionState newState);
    }

    public static class McpClientException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = -1002912289095930440L;

        public McpClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final class ManagerReconnectCallback implements McpConnectionMonitor.ReconnectCallback {
        @Override
        public void onReconnecting(String serverName) {
            updateState(serverName, ConnectionState.RECONNECTING);
        }

        @Override
        public boolean attemptReconnect(String serverName, McpServerConfig config) {
            try {
                updateState(serverName, ConnectionState.CONNECTING);
                var client = new McpClientService(config);
                clients.put(serverName, client);
                updateState(serverName, ConnectionState.CONNECTED);
                return true;
            } catch (Exception e) {
                LOGGER.error("Reconnect failed for server {}: {}", serverName, e.getMessage());
                return false;
            }
        }

        @Override
        public void onReconnectFailed(String serverName) {
            updateState(serverName, ConnectionState.FAILED);
        }
    }
}
