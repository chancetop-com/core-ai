package ai.core.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Monitors MCP client connections with heartbeat checks and automatic reconnection.
 *
 * @author stephen
 */
public class McpConnectionMonitor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpConnectionMonitor.class);

    private final Map<String, AtomicInteger> reconnectAttempts = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> reconnectTasks = new ConcurrentHashMap<>();
    private final Supplier<Map<String, McpServerConfig>> configsSupplier;
    private final Supplier<Map<String, McpClientService>> clientsSupplier;
    private final Function<String, McpClientManager.ConnectionState> stateGetter;
    private final Consumer<String> disconnectionHandler;
    private final ReconnectCallback reconnectCallback;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
    private volatile boolean closed = false;

    public McpConnectionMonitor(
            Supplier<Map<String, McpServerConfig>> configsSupplier,
            Supplier<Map<String, McpClientService>> clientsSupplier,
            Function<String, McpClientManager.ConnectionState> stateGetter,
            Consumer<String> disconnectionHandler,
            ReconnectCallback reconnectCallback) {
        this.configsSupplier = configsSupplier;
        this.clientsSupplier = clientsSupplier;
        this.stateGetter = stateGetter;
        this.disconnectionHandler = disconnectionHandler;
        this.reconnectCallback = reconnectCallback;
    }

    /**
     * Start heartbeat monitoring.
     */
    public void startHeartbeat() {
        if (scheduler != null) {
            LOGGER.warn("Heartbeat scheduler already running");
            return;
        }

        Duration minInterval = configsSupplier.get().values().stream()
            .filter(McpServerConfig::isEnableHeartbeat)
            .map(McpServerConfig::getHeartbeatInterval)
            .min(Duration::compareTo)
            .orElse(Duration.ofSeconds(30));

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatTask = scheduler.scheduleAtFixedRate(
            this::performHeartbeatCheck,
            minInterval.toMillis(),
            minInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        LOGGER.info("Heartbeat monitoring started with interval: {}s", minInterval.toSeconds());
    }

    /**
     * Stop heartbeat monitoring.
     */
    public void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        LOGGER.info("Heartbeat monitoring stopped");
    }

    /**
     * Perform heartbeat check for all connected clients.
     */
    private void performHeartbeatCheck() {
        var clients = clientsSupplier.get();
        var configs = configsSupplier.get();

        for (var entry : clients.entrySet()) {
            String serverName = entry.getKey();
            McpClientService client = entry.getValue();
            McpServerConfig config = configs.get(serverName);

            if (config == null || !config.isEnableHeartbeat()) {
                continue;
            }

            var currentState = stateGetter.apply(serverName);
            if (currentState != McpClientManager.ConnectionState.CONNECTED) {
                continue;
            }

            try {
                boolean alive = client.ping(config.getHeartbeatTimeout());
                if (!alive) {
                    LOGGER.warn("Heartbeat failed for server: {}, initiating reconnect", serverName);
                    triggerReconnect(serverName);
                }
            } catch (Exception e) {
                LOGGER.warn("Heartbeat check error for server {}: {}", serverName, e.getMessage());
                triggerReconnect(serverName);
            }
        }
    }

    private void triggerReconnect(String serverName) {
        disconnectionHandler.accept(serverName);
        var config = configsSupplier.get().get(serverName);
        if (config != null && config.isAutoReconnect() && !closed) {
            scheduleReconnect(serverName);
        }
    }

    /**
     * Schedule a reconnection attempt.
     */
    public void scheduleReconnect(String serverName) {
        var config = configsSupplier.get().get(serverName);
        if (config == null || closed) {
            return;
        }

        cancelReconnectTask(serverName);

        var attempts = reconnectAttempts.computeIfAbsent(serverName, k -> new AtomicInteger(0));
        int currentAttempt = attempts.get();

        if (currentAttempt >= config.getMaxReconnectAttempts()) {
            LOGGER.error("Max reconnect attempts ({}) reached for server: {}",
                config.getMaxReconnectAttempts(), serverName);
            reconnectCallback.onReconnectFailed(serverName);
            return;
        }

        long delayMs = calculateBackoffDelay(config, currentAttempt);
        reconnectCallback.onReconnecting(serverName);

        LOGGER.info("Scheduling reconnect for server {} in {}ms (attempt {}/{})",
            serverName, delayMs, currentAttempt + 1, config.getMaxReconnectAttempts());

        ensureSchedulerRunning();

        ScheduledFuture<?> task = scheduler.schedule(
            () -> attemptReconnect(serverName),
            delayMs,
            TimeUnit.MILLISECONDS
        );

        reconnectTasks.put(serverName, task);
    }

    private void ensureSchedulerRunning() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mcp-heartbeat");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private long calculateBackoffDelay(McpServerConfig config, int attempt) {
        long baseDelay = config.getReconnectInterval().toMillis();
        long maxDelay = config.getReconnectBackoffMax().toMillis();
        long delay = baseDelay * (1L << attempt);
        delay = Math.min(delay, maxDelay);
        double jitter = 0.9 + Math.random() * 0.2;
        return (long) (delay * jitter);
    }

    private void attemptReconnect(String serverName) {
        if (closed) {
            return;
        }

        var config = configsSupplier.get().get(serverName);
        if (config == null) {
            return;
        }

        var attempts = reconnectAttempts.get(serverName);
        int currentAttempt = attempts.incrementAndGet();

        LOGGER.info("Attempting reconnect for server {} (attempt {}/{})",
            serverName, currentAttempt, config.getMaxReconnectAttempts());

        boolean success = reconnectCallback.attemptReconnect(serverName, config);

        if (success) {
            attempts.set(0);
            reconnectTasks.remove(serverName);
            LOGGER.info("Successfully reconnected to server: {}", serverName);
        } else {
            if (currentAttempt < config.getMaxReconnectAttempts()) {
                scheduleReconnect(serverName);
            } else {
                LOGGER.error("All reconnect attempts exhausted for server: {}", serverName);
                reconnectCallback.onReconnectFailed(serverName);
            }
        }
    }

    /**
     * Cancel pending reconnect task.
     */
    public void cancelReconnectTask(String serverName) {
        var task = reconnectTasks.remove(serverName);
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }
    }

    /**
     * Cancel all pending reconnect tasks.
     */
    public void cancelAllReconnectTasks() {
        for (String serverName : reconnectTasks.keySet()) {
            cancelReconnectTask(serverName);
        }
    }

    /**
     * Reset reconnect attempts for a server.
     */
    public void resetReconnectAttempts(String serverName) {
        var attempts = reconnectAttempts.get(serverName);
        if (attempts != null) {
            attempts.set(0);
        }
    }

    /**
     * Initialize tracking for a server.
     */
    public void addServer(String serverName) {
        reconnectAttempts.put(serverName, new AtomicInteger(0));
    }

    /**
     * Remove tracking for a server.
     */
    public void removeServer(String serverName) {
        cancelReconnectTask(serverName);
        reconnectAttempts.remove(serverName);
    }

    /**
     * Check if reconnection is in progress.
     */
    public boolean isReconnecting(String serverName) {
        return reconnectTasks.containsKey(serverName) && !reconnectTasks.get(serverName).isDone();
    }

    @Override
    public void close() {
        closed = true;
        stopHeartbeat();
        cancelAllReconnectTasks();
    }

    /**
     * Callback interface for reconnection events.
     */
    public interface ReconnectCallback {
        void onReconnecting(String serverName);
        boolean attemptReconnect(String serverName, McpServerConfig config);
        void onReconnectFailed(String serverName);
    }
}
