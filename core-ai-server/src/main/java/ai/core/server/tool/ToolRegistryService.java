package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.mcp.client.McpServerConfig;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.ToolType;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class ToolRegistryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryService.class);

    private static final Map<String, List<ToolCall>> BUILTIN_TOOL_SETS = Map.of(
        "builtin-all", BuiltinTools.ALL,
        "builtin-file-operations", BuiltinTools.FILE_OPERATIONS,
        "builtin-file-read-only", BuiltinTools.FILE_READ_ONLY,
        "builtin-multimodal", BuiltinTools.MULTIMODAL,
        "builtin-web", BuiltinTools.WEB,
        "builtin-code-execution", BuiltinTools.CODE_EXECUTION
    );

    private final Map<String, List<ToolCall>> dynamicToolSets = new ConcurrentHashMap<>();
    private final Map<String, ToolRegistry> tools = new ConcurrentHashMap<>();

    @Inject
    MongoCollection<ToolRegistry> toolRegistryCollection;

    public void initialize() {
        var entries = toolRegistryCollection.find(Filters.eq("enabled", true));
        for (var entry : entries) {
            tools.put(entry.id, entry);
            if (entry.type == ToolType.MCP) {
                registerMcpServer(entry);
            }
        }
    }

    public List<ToolRegistry> listTools(String category) {
        if (category != null && !category.isBlank()) {
            return tools.values().stream()
                .filter(t -> category.equals(t.category))
                .toList();
        }
        return List.copyOf(tools.values());
    }

    public ToolRegistry getTool(String id) {
        var tool = tools.get(id);
        if (tool == null) throw new RuntimeException("tool not found, id=" + id);
        return tool;
    }

    public List<String> listCategories() {
        return tools.values().stream()
            .map(t -> t.category)
            .filter(c -> c != null && !c.isBlank())
            .distinct()
            .sorted()
            .toList();
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

        if (configChanged || enabledChanged) {
            applyMcpServerState(entity, configChanged);
        }
        LOGGER.info("updated mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    public void deleteMcpServer(String id) {
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
        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        entity.enabled = false;
        toolRegistryCollection.replace(entity);

        unregisterMcpServer(id);
        LOGGER.info("disabled mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    public List<ToolCall> resolveTools(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) return List.of();

        var result = new ArrayList<ToolCall>();
        for (var toolId : toolIds) {
            var builtinSet = BUILTIN_TOOL_SETS.get(toolId);
            if (builtinSet != null) {
                result.addAll(builtinSet);
                continue;
            }
            var dynamicSet = dynamicToolSets.get(toolId);
            if (dynamicSet != null) {
                result.addAll(dynamicSet);
                continue;
            }

            var entry = tools.get(toolId);
            if (entry == null) continue;

            switch (entry.type) {
                case MCP -> {
                    var mcpManager = McpClientManagerRegistry.getManager();
                    if (mcpManager != null && mcpManager.hasServer(entry.id)) {
                        result.addAll(McpToolCalls.from(mcpManager, List.of(entry.id), null));
                    }
                }
                case BUILTIN -> {
                    var set = BUILTIN_TOOL_SETS.get(entry.config.get("set"));
                    if (set != null) result.addAll(set);
                }
                case API -> {
                    // TODO: implement API tool resolution
                }
                default -> { }
            }
        }
        return result;
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
        if (mcpManager != null && mcpManager.hasServer(serverId)) {
            mcpManager.removeServer(serverId);
        }
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
            if (configChanged) {
                unregisterMcpServer(entity.id);
            }
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
