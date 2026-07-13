package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.mcp.client.McpServerConfig;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import core.framework.mongo.MongoCollection;
import core.framework.util.StopWatch;
import core.framework.web.exception.ConflictException;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles MCP server CRUD, connection lifecycle, state polling, and tool execution.
 *
 * @author stephen
 */
class McpServerOperationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerOperationService.class);

    // Virtual-thread executor for async MCP connect operations.
    private static final ExecutorService MCP_CONNECT_EXECUTOR = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("mcp-connect-", 0).factory()
    );

    private static final Duration CONNECT_OPERATION_TIMEOUT = Duration.ofSeconds(15);
    private static final long TOOL_DETAILS_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final long STATUS_CACHE_TTL_NANOS = Duration.ofSeconds(2).toNanos();

    static boolean isSandboxHosted(ToolRegistryEntry entity) {
        return entity.config != null && "sandbox_hosted".equalsIgnoreCase(entity.config.get("transport"));
    }

    private final Map<String, ToolRegistryEntry> tools;
    private final McpServerConnectionManager mcpConnectionManager;
    private final Map<String, CachedToolDetails> toolDetailsCache = new ConcurrentHashMap<>();
    private final Map<String, CachedState> stateCache = new ConcurrentHashMap<>();
    private MongoCollection<ToolRegistryEntry> toolRegistryCollection;

    McpServerOperationService(Map<String, ToolRegistryEntry> tools,
                              McpServerConnectionManager mcpConnectionManager) {
        this.tools = tools;
        this.mcpConnectionManager = mcpConnectionManager;
    }

    void setToolRegistryCollection(MongoCollection<ToolRegistryEntry> toolRegistryCollection) {
        this.toolRegistryCollection = toolRegistryCollection;
    }

    ToolRegistryEntry createMcpServer(String name, String description, String category,
                                      Map<String, String> config, Boolean enabled) {
        return createMcpServerInternal(name, description, category, config, enabled, null);
    }

    ToolRegistryEntry createMcpServerInternal(String name, String description, String category,
                                              Map<String, String> config, Boolean enabled, String rawConfig) {
        if (findMcpServerByName(name).isPresent()) {
            throw new ConflictException("mcp server name already exists: " + name);
        }
        var configMap = new HashMap<String, Object>(config);
        McpServerConfig.fromMap(name, configMap);

        var entity = new ToolRegistryEntry();
        entity.id = new ObjectId().toHexString();
        entity.name = name;
        entity.description = description;
        entity.category = category;
        entity.type = ToolType.MCP;
        entity.config = config;
        entity.rawConfig = rawConfig;
        entity.enabled = enabled == null || enabled;
        entity.createdAt = ZonedDateTime.now();
        toolRegistryCollection.insert(entity);

        tools.put(entity.id, entity);
        if (Boolean.TRUE.equals(entity.enabled)) {
            if (isSandboxHosted(entity)) {
                mcpConnectionManager.ensureRegisteredOnDiscovery(entity);
            } else {
                mcpConnectionManager.registerMcpServer(entity);
                mcpConnectionManager.warmupMcpServer(entity.id);
            }
        }
        LOGGER.info("created mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    /**
     * Import MCP servers from a standard mcpServers JSON (Claude Desktop format).
     */
    @SuppressWarnings("unchecked")
    List<ToolRegistryEntry> importMcpServers(String rawJson, String category, Boolean enabled) {
        Map<String, Object> parsed = JsonUtil.fromJson(Map.class, rawJson);
        var mcpServers = (Map<String, Object>) parsed.get("mcpServers");
        if (mcpServers == null || mcpServers.isEmpty()) {
            throw new IllegalArgumentException("missing or empty 'mcpServers' key in config");
        }

        var results = new ArrayList<ToolRegistryEntry>();
        for (var entry : mcpServers.entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> serverConf)) {
                LOGGER.warn("skipping invalid mcp server config for '{}', expected object", name);
                continue;
            }
            if (findMcpServerByName(name).isPresent()) {
                LOGGER.warn("skipping duplicate mcp server, name={}", name);
                continue;
            }
            var config = convertImportConfigToMap((Map<String, Object>) serverConf);
            var rawConfig = JsonUtil.toJson(serverConf);
            var entity = createMcpServerInternal(name, null, category, config, enabled, rawConfig);
            results.add(entity);
        }
        LOGGER.info("imported {} mcp servers, category={}, enabled={}", results.size(), category, enabled);
        return results;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    ToolRegistryEntry updateMcpServer(String id, String name, String description, String category,
                                      Map<String, String> config, Boolean enabled, String rawConfig) {
        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        boolean configChanged = false;
        boolean enabledChanged = false;

        if (name != null && !name.equals(entity.name)) {
            if (findMcpServerByName(name).isPresent()) {
                throw new ConflictException("mcp server name already exists: " + name);
            }
            entity.name = name;
        }
        if (description != null) entity.description = description;
        if (category != null) entity.category = category;
        if (config != null) {
            var configMap = new HashMap<String, Object>(config);
            McpServerConfig.fromMap(entity.name, configMap);
            entity.config = config;
            configChanged = true;
        }
        if (rawConfig != null) {
            entity.rawConfig = rawConfig;
        }
        if (enabled != null && !enabled.equals(entity.enabled)) {
            entity.enabled = enabled;
            enabledChanged = true;
        }

        toolRegistryCollection.replace(entity);
        if (configChanged || enabledChanged) mcpConnectionManager.applyMcpServerState(entity, configChanged);
        LOGGER.info("updated mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    void deleteMcpServer(String id) {
        if (id.startsWith(ToolRegistryService.CONFIG_PREFIX)) throw new RuntimeException("cannot delete mcp server from configuration");
        if (id.startsWith(ToolRegistryService.BUILTIN_PREFIX)) throw new RuntimeException("cannot delete builtin tool set");

        var entity = tools.remove(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) {
            tools.put(id, entity);
            throw new RuntimeException("tool is not an mcp server, id=" + id);
        }

        mcpConnectionManager.unregisterMcpServer(id);
        toolRegistryCollection.delete(id);
        LOGGER.info("deleted mcp server, id={}, name={}", id, entity.name);
    }

    ToolRegistryEntry enableMcpServer(String id) {
        invalidateMcpServerCache(id);
        if (id.startsWith(ToolRegistryService.CONFIG_PREFIX)) throw new RuntimeException("cannot enable/disable mcp server from configuration");
        if (id.startsWith(ToolRegistryService.BUILTIN_PREFIX)) throw new RuntimeException("cannot enable/disable builtin tool set");

        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        entity.enabled = true;
        toolRegistryCollection.replace(entity);
        if (isSandboxHosted(entity)) {
            mcpConnectionManager.ensureRegisteredOnDiscovery(entity);
        } else {
            mcpConnectionManager.registerMcpServer(entity);
            mcpConnectionManager.warmupMcpServer(entity.id);
        }
        LOGGER.info("enabled mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    ToolRegistryEntry disableMcpServer(String id) {
        invalidateMcpServerCache(id);
        if (id.startsWith(ToolRegistryService.CONFIG_PREFIX)) throw new RuntimeException("cannot enable/disable mcp server from configuration");
        if (id.startsWith(ToolRegistryService.BUILTIN_PREFIX)) throw new RuntimeException("cannot enable/disable builtin tool set");

        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        entity.enabled = false;
        toolRegistryCollection.replace(entity);
        mcpConnectionManager.unregisterMcpServer(id);
        LOGGER.info("disabled mcp server, id={}, name={}", id, entity.name);
        return entity;
    }

    List<String> listMcpServerTools(String serverId) {
        var entity = tools.get(serverId);
        if (entity == null || entity.type != ToolType.MCP) {
            throw new RuntimeException("mcp server not found or not MCP type, id=" + serverId);
        }
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null || !mcpManager.hasServer(entity.id)) {
            return List.of();
        }
        try {
            return mcpManager.safeListToolNames(entity.id);
        } catch (Exception e) {
            LOGGER.warn("failed to list tools from mcp server, id={}", serverId, e);
            return List.of();
        }
    }

    List<McpSchema.Tool> listMcpServerToolDetails(String serverId) {
        var watch = new StopWatch();
        var entity = requireMcpEntity(serverId);

        var cached = toolDetailsCache.get(serverId);
        if (cached != null && System.nanoTime() - cached.createdAtNanos() < TOOL_DETAILS_CACHE_TTL_NANOS) {
            LOGGER.debug("listMcpServerToolDetails cache hit, id={}", serverId);
            return cached.tools();
        }

        if (isSandboxHosted(entity)) {
            mcpConnectionManager.ensureRegisteredOnDiscovery(entity);
        }

        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null || !mcpManager.hasServer(entity.id)) {
            toolDetailsCache.put(serverId, new CachedToolDetails(List.of(), System.nanoTime()));
            return List.of();
        }

        var currentState = mcpManager.getState(entity.id);
        if (currentState == McpClientManager.ConnectionState.FAILED
            || currentState == McpClientManager.ConnectionState.RECONNECTING) {
            LOGGER.debug("listMcpServerToolDetails short-circuit, id={}, state={}", serverId, currentState);
            toolDetailsCache.put(serverId, new CachedToolDetails(List.of(), System.nanoTime()));
            return List.of();
        }

        try {
            var tools = mcpManager.safeListTools(entity.id);
            toolDetailsCache.put(serverId, new CachedToolDetails(tools, System.nanoTime()));
            LOGGER.debug("listMcpServerToolDetails completed, id={}, tools={}, elapsed={}", serverId, tools.size(), watch.elapsed());
            return tools;
        } catch (Exception e) {
            LOGGER.warn("failed to list tool details from mcp server, id={}, elapsed={}", serverId, watch.elapsed(), e);
            toolDetailsCache.put(serverId, new CachedToolDetails(List.of(), System.nanoTime()));
            return List.of();
        }
    }

    McpClientManager.ConnectionState getMcpServerState(String serverId) {
        var cached = stateCache.get(serverId);
        if (cached != null && System.nanoTime() - cached.createdAtNanos() < STATUS_CACHE_TTL_NANOS) {
            return cached.state();
        }

        var entity = requireMcpEntity(serverId);
        var mcpManager = McpClientManagerRegistry.getManager();
        McpClientManager.ConnectionState state;
        if (mcpManager == null || !mcpManager.hasServer(entity.id)) {
            state = McpClientManager.ConnectionState.NOT_CONNECTED;
        } else {
            state = mcpManager.getState(entity.id);
        }
        stateCache.put(serverId, new CachedState(state, System.nanoTime()));
        return state;
    }

    McpClientManager.ConnectionState connectMcpServer(String serverId) {
        invalidateMcpServerCache(serverId);
        var entity = requireMcpEntity(serverId);
        if (!Boolean.TRUE.equals(entity.enabled)) throw new RuntimeException("mcp server is disabled, id=" + serverId);

        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null) throw new RuntimeException("mcp manager not initialized");

        var currentState = mcpManager.getState(entity.id);
        if (currentState == McpClientManager.ConnectionState.FAILED
            || currentState == McpClientManager.ConnectionState.RECONNECTING) {
            LOGGER.warn("mcp server is in {} state, skipping connect, id={}", currentState, serverId);
            return currentState;
        }
        if (currentState == McpClientManager.ConnectionState.CONNECTED) return currentState;

        if (!isSandboxHosted(entity)) {
            mcpConnectionManager.registerMcpServer(entity);
        }

        currentState = mcpManager.getState(entity.id);
        if (currentState == McpClientManager.ConnectionState.CONNECTED) return currentState;
        if (currentState == McpClientManager.ConnectionState.CONNECTING) {
            return currentState;
        }

        Future<McpClientManager.ConnectionState> future = MCP_CONNECT_EXECUTOR.submit(() -> {
            try {
                if (isSandboxHosted(entity)) {
                    mcpConnectionManager.ensureRegisteredOnDiscovery(entity);
                }
                mcpManager.getClient(entity.id);
            } catch (McpClientManager.McpClientException e) {
                LOGGER.warn("failed to connect mcp server, id={}, reason={}", serverId, e.getMessage());
            } catch (Exception e) {
                LOGGER.warn("failed to connect mcp server, id={}", serverId, e);
            }
            return mcpManager.getState(entity.id);
        });

        try {
            return future.get(CONNECT_OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.info("connect mcp server timed out after {}, continuing in background, id={}",
                CONNECT_OPERATION_TIMEOUT, serverId);
            return mcpManager.getState(entity.id);
        } catch (CancellationException e) {
            return mcpManager.getState(entity.id);
        } catch (ExecutionException e) {
            LOGGER.warn("failed to connect mcp server, id={}", serverId, e.getCause());
            return mcpManager.getState(entity.id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return mcpManager.getState(entity.id);
        }
    }

    ToolCallResult callMcpServerTool(String serverId, String toolName, String argumentsJson) {
        var entity = requireMcpEntity(serverId);
        if (isSandboxHosted(entity)) {
            mcpConnectionManager.ensureRegisteredOnDiscovery(entity);
        }
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null || !mcpManager.hasServer(entity.id)) {
            throw new RuntimeException("mcp server not connected, id=" + serverId);
        }
        var payload = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        return mcpManager.safeCallTool(entity.id, toolName, payload);
    }

    private void invalidateMcpServerCache(String serverId) {
        toolDetailsCache.remove(serverId);
        stateCache.remove(serverId);
    }

    private ToolRegistryEntry requireMcpEntity(String serverId) {
        var entity = tools.get(serverId);
        if (entity == null || entity.type != ToolType.MCP) {
            throw new RuntimeException("mcp server not found or not MCP type, id=" + serverId);
        }
        return entity;
    }

    private java.util.Optional<ToolRegistryEntry> findMcpServerByName(String name) {
        return toolRegistryCollection.findOne(Filters.and(
                Filters.eq("type", ToolType.MCP.name()),
                Filters.eq("name", name)));
    }

    // Convert a single server config from the import JSON to Map<String,String>
    @SuppressWarnings("unchecked")
    private Map<String, String> convertImportConfigToMap(Map<String, Object> serverConf) {
        var result = new HashMap<String, String>();
        result.put("transport", "sandbox_hosted");

        var command = serverConf.get("command");
        if (command instanceof String cmd && !cmd.isBlank()) {
            result.put("command", cmd);
        } else {
            throw new IllegalArgumentException("command is required");
        }

        for (var e : serverConf.entrySet()) {
            if (result.containsKey(e.getKey()) || e.getValue() == null) continue;
            var value = e.getValue();
            if (value instanceof String s) {
                result.put(e.getKey(), s);
            } else if (value instanceof Number || value instanceof Boolean) {
                result.put(e.getKey(), value.toString());
            } else {
                result.put(e.getKey(), JsonUtil.toJson(value));
            }
        }
        return result;
    }

    private record CachedToolDetails(List<McpSchema.Tool> tools, long createdAtNanos) {
    }

    private record CachedState(McpClientManager.ConnectionState state, long createdAtNanos) {
    }
}
