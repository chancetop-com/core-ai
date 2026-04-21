package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.mcp.client.McpServerConfig;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class ToolRegistryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryService.class);
    private static final String CONFIG_PREFIX = "config:";
    private static final String BUILTIN_PREFIX = "builtin:";
    private static final String API_TOOL_ID = "builtin-service-api";

    static final Map<String, List<ToolCall>> BUILTIN_TOOL_SETS = Map.of(
        "builtin-all", BuiltinTools.ALL,
        "builtin-planning", BuiltinTools.PLANNING,
        "builtin-file-operations", BuiltinTools.FILE_OPERATIONS,
        "builtin-file-read-only", BuiltinTools.FILE_READ_ONLY,
        "builtin-multimodal", BuiltinTools.MULTIMODAL,
        "builtin-web", BuiltinTools.WEB,
        "builtin-code-execution", BuiltinTools.CODE_EXECUTION
    );

    private final Map<String, ToolRegistry> tools = new ConcurrentHashMap<>();
    private final Map<String, List<ToolCall>> dynamicToolSets = new ConcurrentHashMap<>();
    private ToolRefResolver toolRefResolver;

    @Inject
    MongoCollection<ToolRegistry> toolRegistryCollection;

    @Inject
    ApiDefinitionService apiDefinitionService;

    private InternalApiToolLoader internalApiToolLoader;

    public void initialize(String mcpServersJson) {
        loadBuiltinTools();
        loadServiceApiTools();
        loadConfigMcpServers(mcpServersJson);
        loadDatabaseTools();
        toolRefResolver = new ToolRefResolver(tools, internalApiToolLoader);
    }

    private void loadServiceApiTools() {
        try {
            if (apiDefinitionService == null) {
                LOGGER.warn("ApiDefinitionService not injected, skipping Service API tools");
                return;
            }
            internalApiToolLoader = new InternalApiToolLoader(apiDefinitionService);
            var apiTools = internalApiToolLoader.load();

            var registry = new ToolRegistry();
            registry.id = API_TOOL_ID;
            registry.name = "service-api";
            registry.type = ToolType.API;
            registry.category = "builtin";
            registry.enabled = true;
            registry.description = "Internal Service API tools loaded from configured API definitions";
            registry.config = Map.of("set", API_TOOL_ID);
            tools.put(registry.id, registry);

            dynamicToolSets.put(API_TOOL_ID, apiTools);
            LOGGER.info("loaded {} Service API tools", apiTools.size());
        } catch (Exception e) {
            LOGGER.error("failed to load Service API tools", e);
        }
    }

    private void loadBuiltinTools() {
        for (var entry : BUILTIN_TOOL_SETS.entrySet()) {
            var registry = new ToolRegistry();
            registry.id = BUILTIN_PREFIX + entry.getKey();
            registry.name = entry.getKey();
            registry.type = ToolType.BUILTIN;
            registry.category = "builtin";
            registry.enabled = true;
            registry.description = "Built-in tool set: " + entry.getKey();
            registry.config = Map.of("set", entry.getKey());
            tools.put(registry.id, registry);
            LOGGER.debug("loaded builtin toolset: {}", entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadConfigMcpServers(String mcpServersJson) {
        if (mcpServersJson == null || mcpServersJson.isBlank()) {
            LOGGER.debug("no mcp.servers.json configured");
            return;
        }

        try {
            var servers = (Map<String, Map<String, Object>>) JsonUtil.fromJson(Map.class, mcpServersJson);
            for (var entry : servers.entrySet()) {
                var name = entry.getKey();
                var config = entry.getValue();
                var registry = new ToolRegistry();
                registry.id = CONFIG_PREFIX + name;
                registry.name = name;
                registry.type = ToolType.MCP;
                registry.category = "config";
                registry.enabled = true;
                registry.description = "MCP server from configuration";
                registry.config = convertConfigToStringMap(config);
                tools.put(registry.id, registry);
                LOGGER.debug("loaded config mcp server: {}", name);
            }
            LOGGER.info("loaded {} mcp servers from configuration", servers.size());
        } catch (Exception e) {
            LOGGER.error("failed to parse mcp.servers.json from configuration", e);
        }
    }

    private Map<String, String> convertConfigToStringMap(Map<String, Object> config) {
        var result = new HashMap<String, String>();
        if (config != null) {
            for (var entry : config.entrySet()) {
                if (entry.getValue() != null) result.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return result;
    }

    private void loadDatabaseTools() {
        for (var entry : toolRegistryCollection.find(Filters.eq("enabled", true))) {
            if (tools.containsKey(entry.id)) {
                LOGGER.debug("skipping duplicate tool: {}", entry.id);
                continue;
            }
            tools.put(entry.id, entry);
            if (entry.type == ToolType.MCP) registerMcpServer(entry);
        }
    }

    public List<ToolRegistry> listTools(String category) {
        if (category != null && !category.isBlank()) {
            return tools.values().stream().filter(t -> category.equals(t.category)).toList();
        }
        return List.copyOf(tools.values());
    }

    public ToolRegistry getTool(String id) {
        var tool = tools.get(id);
        if (tool == null) throw new RuntimeException("tool not found, id=" + id);
        return tool;
    }

    public List<String> listCategories() {
        return tools.values().stream().map(t -> t.category).filter(c -> c != null && !c.isBlank()).distinct().sorted().toList();
    }

    public void registerToolSet(String name, List<ToolCall> toolCalls) {
        dynamicToolSets.put(name, toolCalls);
    }

    public ToolRegistry createMcpServer(String name, String description, String category, Map<String, String> config, Boolean enabled) {
        var configMap = new HashMap<String, Object>(config);
        McpServerConfig.fromMap(name, configMap);

        var entity = new ToolRegistry();
        entity.id = new ObjectId().toHexString();
        entity.name = name;
        entity.description = description;
        entity.category = category;
        entity.type = ToolType.MCP;
        entity.config = config;
        entity.enabled = enabled == null || enabled;
        entity.createdAt = ZonedDateTime.now();
        toolRegistryCollection.insert(entity);

        tools.put(entity.id, entity);
        if (entity.enabled) {
            registerMcpServer(entity);
            warmupMcpServer(entity.id);
        }
        LOGGER.info("created mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    public ToolRegistry updateMcpServer(String id, String name, String description, String category, Map<String, String> config, Boolean enabled) {
        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        boolean configChanged = false;
        boolean enabledChanged = false;

        if (name != null) entity.name = name;
        if (description != null) entity.description = description;
        if (category != null) entity.category = category;
        if (config != null) {
            var configMap = new HashMap<String, Object>(config);
            McpServerConfig.fromMap(entity.name, configMap);
            entity.config = config;
            configChanged = true;
        }
        if (enabled != null && !enabled.equals(entity.enabled)) {
            entity.enabled = enabled;
            enabledChanged = true;
        }

        toolRegistryCollection.replace(entity);
        if (configChanged || enabledChanged) applyMcpServerState(entity, configChanged);
        LOGGER.info("updated mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    public void deleteMcpServer(String id) {
        if (id.startsWith(CONFIG_PREFIX)) throw new RuntimeException("cannot delete mcp server from configuration");
        if (id.startsWith(BUILTIN_PREFIX)) throw new RuntimeException("cannot delete builtin tool set");

        var entity = tools.remove(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) {
            tools.put(id, entity);
            throw new RuntimeException("tool is not an mcp server, id=" + id);
        }

        unregisterMcpServer(id);
        toolRegistryCollection.delete(id);
        LOGGER.info("deleted mcp server, id={}, name={}", id, entity.name);
    }

    public ToolRegistry enableMcpServer(String id) {
        if (id.startsWith(CONFIG_PREFIX)) throw new RuntimeException("cannot enable/disable mcp server from configuration");
        if (id.startsWith(BUILTIN_PREFIX)) throw new RuntimeException("cannot enable/disable builtin tool set");

        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        entity.enabled = true;
        toolRegistryCollection.replace(entity);
        registerMcpServer(entity);
        warmupMcpServer(entity.id);
        LOGGER.info("enabled mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    public ToolRegistry disableMcpServer(String id) {
        if (id.startsWith(CONFIG_PREFIX)) throw new RuntimeException("cannot enable/disable mcp server from configuration");
        if (id.startsWith(BUILTIN_PREFIX)) throw new RuntimeException("cannot enable/disable builtin tool set");

        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        entity.enabled = false;
        toolRegistryCollection.replace(entity);
        unregisterMcpServer(id);
        LOGGER.info("disabled mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    public List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs) {
        return toolRefResolver != null ? toolRefResolver.resolve(toolRefs) : List.of();
    }

    public List<String> extractAgentIds(List<ToolRef> toolRefs) {
        if (toolRefs == null || toolRefs.isEmpty()) return List.of();
        return toolRefs.stream()
                .filter(ref -> ref.type == ToolSourceType.AGENT)
                .map(ref -> ref.id)
                .toList();
    }

    private void registerMcpServer(ToolRegistry entry) {
        var mcpManager = getOrCreateMcpManager();
        if (mcpManager.hasServer(entry.id)) return;

        var configMap = new HashMap<String, Object>(entry.config);
        var serverConfig = McpServerConfig.fromMap(entry.id, configMap);
        mcpManager.addServer(serverConfig);
    }

    private void unregisterMcpServer(String serverId) {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager != null && mcpManager.hasServer(serverId)) mcpManager.removeServer(serverId);
    }

    private void warmupMcpServer(String serverId) {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager != null && mcpManager.hasServer(serverId)) {
            try {
                mcpManager.getClient(serverId);
            } catch (Exception e) {
                LOGGER.error("failed to warmup mcp server, id={}", serverId, e);
            }
        }
    }

    private void applyMcpServerState(ToolRegistry entity, boolean configChanged) {
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

    public InternalApiToolLoader getInternalApiToolLoader() {
        return internalApiToolLoader;
    }

    public List<InternalApiToolLoader.ApiAppInfo> listServiceApiApps() {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.listApiApps();
    }

    public void reloadApiTools() {
        if (toolRefResolver != null) toolRefResolver.reloadApiTools();
    }
}
