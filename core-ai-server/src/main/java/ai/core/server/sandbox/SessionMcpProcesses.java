package ai.core.server.sandbox;

import ai.core.mcp.client.McpClientManager;
import ai.core.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Per-session MCP managers and the MCP servers started inside that session's sandbox: managers are
 * created on demand when the session resolves its tool refs, and everything a sandbox started has to
 * be stopped when the sandbox is released.
 * <p>
 * Starts can run in the background (a cold {@code npx} download takes minutes). The in-flight
 * starts are kept here so a turn can wait for them before materializing its tools instead of running
 * without the MCP tools the agent was configured with.
 *
 * @author stephen
 */
public class SessionMcpProcesses {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMcpProcesses.class);

    private static void close(McpClientManager manager) {
        try {
            manager.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close session mcp manager: {}", e.getMessage());
        }
    }

    private final Map<String, McpClientManager> managers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> startedServerIds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CompletableFuture<Boolean>>> startingServers = new ConcurrentHashMap<>();

    public McpClientManager managerFor(String sessionId) {
        return managers.computeIfAbsent(sessionId, sid -> new McpClientManager());
    }

    public void record(String sessionId, String serverId) {
        startedServerIds.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(serverId);
    }

    /**
     * Marks a sandbox-hosted MCP server as starting on this session. The returned future completes
     * with {@code true} once the process is ready, or {@code false} when the start failed.
     */
    public CompletableFuture<Boolean> beginStart(String sessionId, String serverId) {
        return startingServers.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(serverId, k -> new CompletableFuture<>());
    }

    public void finishStart(String sessionId, String serverId, boolean started) {
        var perSession = startingServers.get(sessionId);
        if (perSession == null) return;
        var future = perSession.remove(serverId);
        if (future != null) future.complete(started);
        if (perSession.isEmpty()) startingServers.remove(sessionId, perSession);
    }

    /**
     * Blocks until every in-flight sandbox MCP start for the session has settled, bounded by
     * {@code timeoutSeconds}. Never throws: a slow or failed start only leaves the tools out of this
     * turn, exactly as before, but the turn must not hang on it.
     */
    public void awaitStarts(String sessionId, int timeoutSeconds) {
        var perSession = startingServers.get(sessionId);
        if (perSession == null || perSession.isEmpty()) return;

        var futures = List.copyOf(perSession.values());
        var start = System.currentTimeMillis();
        LOGGER.info("waiting for sandbox-hosted mcp servers before the turn, sessionId={}, servers={}",
                sessionId, perSession.keySet());
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            LOGGER.info("sandbox-hosted mcp servers ready for the turn, sessionId={}, waitedMs={}",
                    sessionId, System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("interrupted while waiting for sandbox-hosted mcp servers, sessionId={}", sessionId);
        } catch (TimeoutException e) {
            LOGGER.warn("sandbox-hosted mcp servers did not start in time, sessionId={}, timeoutSeconds={}",
                    sessionId, timeoutSeconds);
        } catch (ExecutionException e) {
            LOGGER.warn("sandbox-hosted mcp server start failed while waiting, sessionId={}: {}",
                    sessionId, e.getMessage());
        }
    }

    void stopAll(String sessionId, Sandbox sandbox) {
        var serverIds = startedServerIds.remove(sessionId);
        if (sandbox != null && serverIds != null) {
            for (var id : serverIds) {
                try {
                    sandbox.stopMcpServer(id);
                } catch (Exception e) {
                    LOGGER.warn("failed to stop mcp server in session sandbox: session={}, serverId={}: {}", sessionId, id, e.getMessage());
                }
            }
        }
        var manager = managers.remove(sessionId);
        if (manager != null) {
            close(manager);
        }
        var pending = startingServers.remove(sessionId);
        if (pending != null) {
            for (var future : pending.values()) {
                future.complete(Boolean.FALSE);
            }
        }
    }

    void closeAll() {
        for (var manager : managers.values()) {
            close(manager);
        }
        managers.clear();
        startedServerIds.clear();
        for (var pending : startingServers.values()) {
            for (var future : pending.values()) {
                future.complete(Boolean.FALSE);
            }
        }
        startingServers.clear();
    }
}
