package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpServerConfig;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
class McpServerConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerConnectionManager.class);

    private static boolean isSandboxHosted(Map<String, String> config) {
        var transport = config.get("transport");
        return "sandbox_hosted".equalsIgnoreCase(transport);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseArgsList(Object argsValue) {
        if (argsValue == null) return List.of();
        if (argsValue instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        // args might be a JSON array string (when stored in Map<String,String>)
        var str = argsValue.toString().trim();
        if (str.startsWith("[") && str.endsWith("]")) {
            try {
                var parsed = ai.core.utils.JsonUtil.fromJson(List.class, str);
                return (List<String>) parsed;
            } catch (Exception e) {
                LOGGER.warn("failed to parse args as JSON array, treating as single arg: {}", str);
            }
        }
        return List.of(str);
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private final SandboxService sandboxService;
    private final ApplicationMcpManager applicationMcpManager;

    McpServerConnectionManager(SandboxService sandboxService, ApplicationMcpManager applicationMcpManager) {
        this.sandboxService = sandboxService;
        this.applicationMcpManager = applicationMcpManager;
    }

    // Registers a non-sandbox MCP server (STDIO or HTTP) with the global manager.
    // Sandbox-hosted entries need an active sandbox and are deliberately ignored here —
    // they're picked up lazily by ensureRegisteredOnDiscovery (tool browsing UI) or
    // registerOnSession (agent run).
    void registerMcpServer(ToolRegistryEntry entry) {
        if (isSandboxHosted(entry.config)) return;
        var mcpManager = getOrCreateGlobalMcpManager();
        if (mcpManager.hasServer(entry.id)) return;

        var configMap = new HashMap<String, Object>();
        configMap.putAll(entry.config);
        var serverConfig = McpServerConfig.fromMap(entry.id, configMap);
        mcpManager.addServer(serverConfig);
    }

    // Idempotently start a sandbox-hosted MCP on the discovery sandbox and register
    // it in the global manager. Used by the frontend's connect / list-tools flow.
    boolean ensureRegisteredOnDiscovery(ToolRegistryEntry entry) {
        if (!isSandboxHosted(entry.config)) return false;
        var mcpManager = getOrCreateGlobalMcpManager();
        if (mcpManager.hasServer(entry.id)) return true;

        if (sandboxService == null) {
            LOGGER.warn("sandbox service not available, cannot register discovery mcp server, id={}", entry.id);
            return false;
        }
        try {
            var sandboxClient = sandboxService.getDiscoverySandboxClient();
            var configMap = new HashMap<String, Object>();
            configMap.putAll(entry.config);
            var command = (String) configMap.get("command");
            if (command == null || command.isBlank()) {
                LOGGER.warn("command is required for sandbox-hosted mcp server, id={}", entry.id);
                return false;
            }
            var args = parseArgsList(configMap.get("args"));
            var env = McpServerConfig.parseEnv(configMap.get("env"));
            sandboxClient.startMcpServer(entry.id, command, args, env);
            var serverConfig = discoverySandboxConfig(entry, sandboxClient.getBaseUrl());
            mcpManager.addServer(serverConfig);
            LOGGER.info("registered sandbox-hosted mcp server on discovery sandbox, id={}", entry.id);
            return true;
        } catch (Exception e) {
            LOGGER.warn("failed to register sandbox-hosted mcp server on discovery, id={}: {}", entry.id, e.getMessage());
            return false;
        }
    }

    private McpServerConfig discoverySandboxConfig(ToolRegistryEntry entry, String baseUrl) {
        var configMap = new HashMap<String, Object>();
        configMap.putAll(entry.config);
        var resolved = new HashMap<String, Object>();
        resolved.put("url", baseUrl);
        resolved.put("endpoint", "mcp");
        resolved.put("headers", Map.of("X-Mcp-Server-Id", entry.id));
        resolved.put("transport", "streamable_http");
        resolved.put("connectTimeout", (long) SandboxConstants.SESSION_MCP_CONNECT_TIMEOUT_SECONDS);
        resolved.put("initializationTimeout", (long) SandboxConstants.SESSION_MCP_INIT_TIMEOUT_SECONDS);
        copyIfPresent(configMap, resolved, "requestTimeout");
        copyIfPresent(configMap, resolved, "initializationTimeout");
        copyIfPresent(configMap, resolved, "connectTimeout");
        return McpServerConfig.fromMap(entry.id, resolved);
    }

    private McpServerConfig buildSandboxBackedConfig(ToolRegistryEntry entry, Sandbox sandbox) {
        var configMap = new HashMap<String, Object>();
        configMap.putAll(entry.config);
        var resolved = new HashMap<String, Object>();
        resolved.put("url", sandbox.getMcpEndpoint());
        resolved.put("endpoint", "mcp");
        resolved.put("headers", Map.of("X-Mcp-Server-Id", entry.id));
        resolved.put("transport", "streamable_http");
        resolved.put("connectTimeout", (long) SandboxConstants.SESSION_MCP_CONNECT_TIMEOUT_SECONDS);
        resolved.put("initializationTimeout", (long) SandboxConstants.SESSION_MCP_INIT_TIMEOUT_SECONDS);
        copyIfPresent(configMap, resolved, "requestTimeout");
        copyIfPresent(configMap, resolved, "initializationTimeout");
        copyIfPresent(configMap, resolved, "connectTimeout");
        return McpServerConfig.fromMap(entry.id, resolved);
    }

    // Register a sandbox-hosted MCP server in a session-scoped manager, starting the
    // MCP process on the given session sandbox. Each session gets its own manager
    // and child process so concurrent sessions don't collide.
    boolean registerOnSession(ToolRegistryEntry entry, McpClientManager sessionManager, Sandbox sandbox) {
        return registerOnSession(entry, sessionManager, sandbox, SandboxConstants.MCP_STARTUP_TIMEOUT_SECONDS);
    }

    // Register with a specific startup timeout — session creation uses a shorter timeout
    // so a stuck MCP server does not block session creation for minutes.
    boolean registerOnSession(ToolRegistryEntry entry, McpClientManager sessionManager, Sandbox sandbox, int startupTimeoutSeconds) {
        if (!isSandboxHosted(entry.config)) return false;
        if (sessionManager.hasServer(entry.id)) return true;
        try {
            var configMap = new HashMap<String, Object>();
            configMap.putAll(entry.config);
            var command = (String) configMap.get("command");
            if (command == null || command.isBlank()) {
                LOGGER.warn("command is required for sandbox-hosted mcp server, id={}", entry.id);
                return false;
            }
            var args = parseArgsList(configMap.get("args"));
            var env = McpServerConfig.parseEnv(configMap.get("env"));
            sandbox.startMcpServer(entry.id, command, args, env, startupTimeoutSeconds);
            var serverConfig = buildSandboxBackedConfig(entry, sandbox);
            sessionManager.addServer(serverConfig);
            LOGGER.info("registered sandbox-hosted mcp server on session sandbox, id={}, ip={}, startupTimeout={}s", entry.id, sandbox.ip(), startupTimeoutSeconds);
            return true;
        } catch (Exception e) {
            LOGGER.warn("failed to register sandbox-hosted mcp server on session, id={}: {}", entry.id, e.getMessage());
            return false;
        }
    }

    void unregisterMcpServer(String serverId) {
        var mcpManager = applicationMcpManager.get();
        if (mcpManager != null && mcpManager.hasServer(serverId)) mcpManager.removeServer(serverId);
    }

    void warmupMcpServer(String serverId) {
        var mcpManager = applicationMcpManager.get();
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

    void applyMcpServerState(ToolRegistryEntry entity, boolean configChanged) {
        // Sandbox-hosted entries aren't held by the global manager; just drop any
        // discovery-side registration so the next connect/listTools re-resolves.
        // In-flight session managers keep their own clients until session release.
        if (isSandboxHosted(entity.config)) {
            unregisterMcpServer(entity.id);
            return;
        }
        if (entity.enabled) {
            if (configChanged) unregisterMcpServer(entity.id);
            registerMcpServer(entity);
            warmupMcpServer(entity.id);
        } else {
            unregisterMcpServer(entity.id);
        }
    }

    private McpClientManager getOrCreateGlobalMcpManager() {
        return applicationMcpManager.getOrCreate();
    }
}
