package ai.core.server.sandbox;

import ai.core.mcp.client.McpClientManager;
import ai.core.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session MCP managers and the MCP servers started inside that session's sandbox: managers are
 * created on demand when the session resolves its tool refs, and everything a sandbox started has to
 * be stopped when the sandbox is released.
 *
 * @author stephen
 */
class SessionMcpProcesses {
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

    McpClientManager managerFor(String sessionId) {
        return managers.computeIfAbsent(sessionId, sid -> new McpClientManager());
    }

    void record(String sessionId, String serverId) {
        startedServerIds.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(serverId);
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
    }

    void closeAll() {
        for (var manager : managers.values()) {
            close(manager);
        }
        managers.clear();
        startedServerIds.clear();
    }
}
