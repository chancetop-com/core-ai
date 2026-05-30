package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.mcp.client.McpServerConfig;
import ai.core.server.domain.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

/**
 * @author stephen
 */
class McpServerConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerConnectionManager.class);

    void registerMcpServer(ToolRegistry entry) {
        var mcpManager = getOrCreateMcpManager();
        if (mcpManager.hasServer(entry.id)) return;

        var configMap = new HashMap<String, Object>(entry.config);
        var serverConfig = McpServerConfig.fromMap(entry.id, configMap);
        mcpManager.addServer(serverConfig);
    }

    void unregisterMcpServer(String serverId) {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager != null && mcpManager.hasServer(serverId)) mcpManager.removeServer(serverId);
    }

    void warmupMcpServer(String serverId) {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null || !mcpManager.hasServer(serverId)) return;
        var state = mcpManager.getState(serverId);
        if (state == McpClientManager.ConnectionState.FAILED
            || state == McpClientManager.ConnectionState.RECONNECTING
            || state == McpClientManager.ConnectionState.CONNECTING) {
            return;
        }
        try {
            mcpManager.getClient(serverId);
        } catch (Exception e) {
            LOGGER.warn("failed to warmup mcp server, id={}, reason={}", serverId, e.getMessage());
        }
    }

    void applyMcpServerState(ToolRegistry entity, boolean configChanged) {
        if (entity.enabled) {
            if (configChanged) unregisterMcpServer(entity.id);
            registerMcpServer(entity);
            warmupMcpServer(entity.id);
        } else {
            unregisterMcpServer(entity.id);
        }
    }

    private McpClientManager getOrCreateMcpManager() {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null) {
            mcpManager = new McpClientManager();
            McpClientManagerRegistry.setManager(mcpManager);
        }
        return mcpManager;
    }
}
