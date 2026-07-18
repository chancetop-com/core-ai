package ai.core.server.tool;

import ai.core.api.server.tool.UpdateMcpServerRequest;
import ai.core.mcp.client.McpClientManager;
import ai.core.media.MediaProvider;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.sandbox.SandboxService;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.github.GitHubTokenProvider;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
@SuppressFBWarnings("CFS_CONFUSING_FUNCTION_SEMANTICS")
public class ToolRegistryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryService.class);
    static final String CONFIG_PREFIX = "config:";
    static final String BUILTIN_PREFIX = "builtin:";
    private static final String API_TOOL_ID = "builtin-service-api";

    private final Map<String, ToolRegistryEntry> tools = new ConcurrentHashMap<>();
    private final Map<String, List<ToolCall>> dynamicToolSets = new ConcurrentHashMap<>();
    private McpServerConnectionManager mcpConnectionManager;
    private ToolRefResolutionService resolutionService;
    private McpServerOperationService mcpOperationService;

    @Inject
    MongoCollection<ToolRegistryEntry> toolRegistryCollection;

    @Inject
    ApiDefinitionService apiDefinitionService;

    private InternalApiToolLoader internalApiToolLoader;

    @Inject
    SandboxService sandboxService;
    @Inject
    MediaProvider mediaProvider;

    private GitHubTokenProvider gitHubTokenProvider;

    private void initializeDependencies() {
        if (mcpConnectionManager != null) return;
        mcpConnectionManager = new McpServerConnectionManager(sandboxService);
        resolutionService = new ToolRefResolutionService(tools, dynamicToolSets, mcpConnectionManager, sandboxService, mediaProvider, gitHubTokenProvider);
        mcpOperationService = new McpServerOperationService(tools, mcpConnectionManager);
    }

    public void setGitHubTokenProvider(GitHubTokenProvider gitHubTokenProvider) {
        this.gitHubTokenProvider = gitHubTokenProvider;
    }

    public void initialize(String mcpServersJson) {
        initializeDependencies();
        mcpOperationService.setToolRegistryCollection(toolRegistryCollection);
        loadBuiltinTools();
        loadServiceApiTools();
        resolutionService.setInternalApiToolLoader(internalApiToolLoader);
        loadConfigMcpServers(mcpServersJson);
        loadDatabaseTools();
    }

    private void loadServiceApiTools() {
        try {
            if (apiDefinitionService == null) {
                LOGGER.warn("ApiDefinitionService not injected, skipping Service API tools");
                return;
            }
            internalApiToolLoader = new InternalApiToolLoader(apiDefinitionService);
            var apiTools = internalApiToolLoader.load();

            var registry = new ToolRegistryEntry();
            registry.id = API_TOOL_ID;
            registry.name = "service-api";
            registry.type = ToolType.API;
            registry.category = "builtin";
            registry.enabled = Boolean.TRUE;
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
        for (var entry : BuiltinTools.GROUPED_SETS.entrySet()) {
            var registry = new ToolRegistryEntry();
            registry.id = BUILTIN_PREFIX + entry.getKey();
            registry.name = entry.getKey();
            registry.type = ToolType.BUILTIN;
            registry.category = "builtin";
            registry.enabled = Boolean.TRUE;
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
                var registry = new ToolRegistryEntry();
                registry.id = CONFIG_PREFIX + name;
                registry.name = name;
                registry.type = ToolType.MCP;
                registry.category = "config";
                registry.enabled = Boolean.TRUE;
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
        for (var entry : toolRegistryCollection.find(Filters.eq("enabled", Boolean.TRUE))) {
            if (tools.containsKey(entry.id)) {
                LOGGER.debug("skipping duplicate tool: {}", entry.id);
                continue;
            }
            tools.put(entry.id, entry);
            if (entry.type == ToolType.MCP) mcpConnectionManager.registerMcpServer(entry);
        }
    }

    public void syncDatabaseTools() {
        var dbEntries = toolRegistryCollection.find(Filters.eq("enabled", Boolean.TRUE));
        var dbEntryIds = new HashSet<String>();

        for (var dbEntry : dbEntries) {
            if (dbEntry.type != ToolType.MCP) continue;
            dbEntryIds.add(dbEntry.id);

            var memEntry = tools.get(dbEntry.id);
            if (memEntry == null) {
                tools.put(dbEntry.id, dbEntry);
                if (McpServerOperationService.isSandboxHosted(dbEntry)) {
                    mcpConnectionManager.ensureRegisteredOnDiscovery(dbEntry);
                } else {
                    mcpConnectionManager.registerMcpServer(dbEntry);
                    mcpConnectionManager.warmupMcpServer(dbEntry.id);
                }
                LOGGER.info("synced new mcp server from db, id={}, name={}", dbEntry.id, dbEntry.name);
            } else if (!dbEntry.enabled.equals(memEntry.enabled) || !dbEntry.config.equals(memEntry.config)) {
                tools.put(dbEntry.id, dbEntry);
                mcpConnectionManager.applyMcpServerState(dbEntry, !dbEntry.config.equals(memEntry.config));
                if (McpServerOperationService.isSandboxHosted(dbEntry) && dbEntry.enabled) {
                    mcpConnectionManager.ensureRegisteredOnDiscovery(dbEntry);
                }
                LOGGER.info("synced updated mcp server from db, id={}, name={}", dbEntry.id, dbEntry.name);
            }
        }

        var toRemove = new ArrayList<String>();
        for (var e : tools.entrySet()) {
            var id = e.getKey();
            var entry = e.getValue();
            if (entry.type != ToolType.MCP) continue;
            if (id.startsWith(CONFIG_PREFIX) || id.startsWith(BUILTIN_PREFIX)) continue;
            if (!dbEntryIds.contains(id)) toRemove.add(id);
        }
        for (var id : toRemove) {
            mcpConnectionManager.unregisterMcpServer(id);
            tools.remove(id);
            LOGGER.info("synced removal of mcp server from db, id={}", id);
        }
    }

    public List<ToolRegistryEntry> listTools(String category) {
        if (category != null && !category.isBlank()) {
            return tools.values().stream().filter(t -> category.equals(t.category)).toList();
        }
        return List.copyOf(tools.values());
    }

    public ToolRegistryEntry getTool(String id) {
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

    public List<BuiltinToolInfo> listBuiltinGroupTools(String groupId) {
        var tcs = dynamicToolSets.get(groupId);
        if (tcs == null) return List.of();
        return tcs.stream().map(t -> {
            var schema = t.toJsonSchema();
            return new BuiltinToolInfo(t.getName(), t.getDescription(),
                schema != null ? JsonUtil.toJson(schema) : null);
        }).toList();
    }

    /**
     * Registers a builtin tool group so it appears in the tool registry UI and can be
     * dynamically configured for agents.
     */
    public void registerBuiltinToolGroup(String name, String category, String description, List<ToolCall> toolCalls) {
        dynamicToolSets.put(name, toolCalls);
        var registry = new ToolRegistryEntry();
        registry.id = name;
        registry.name = name.substring(BUILTIN_PREFIX.length());
        registry.type = ToolType.BUILTIN;
        registry.category = category;
        registry.enabled = Boolean.TRUE;
        registry.description = description;
        registry.config = Map.of();
        registry.createdAt = ZonedDateTime.now();
        tools.put(registry.id, registry);
        LOGGER.info("registered builtin tool group, name={}, category={}, tools={}", name, category, toolCalls.size());
    }

    // ── Delegates to ToolRefResolutionService ──────────────────────────────────

    public List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs) {
        return resolutionService.resolveToolRefs(toolRefs);
    }

    public List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs, String sessionId) {
        return resolutionService.resolveToolRefs(toolRefs, sessionId);
    }

    public ToolRegistry resolveToToolRegistry(List<ToolRef> toolRefs, String sessionId) {
        return resolutionService.resolveToToolRegistry(toolRefs, sessionId);
    }

    public List<String> extractAgentIds(List<ToolRef> toolRefs) {
        return resolutionService.extractAgentIds(toolRefs);
    }

    // ── Delegates to McpServerOperationService ─────────────────────────────────

    public ToolRegistryEntry createMcpServer(String name, String description, String category,
                                             Map<String, String> config, Boolean enabled) {
        return mcpOperationService.createMcpServer(name, description, category, config, enabled);
    }

    public List<ToolRegistryEntry> importMcpServers(String rawJson, String category, Boolean enabled) {
        return mcpOperationService.importMcpServers(rawJson, category, enabled);
    }

    public ToolRegistryEntry updateMcpServer(String id, UpdateMcpServerRequest request) {
        return mcpOperationService.updateMcpServer(id, request);
    }

    public void deleteMcpServer(String id) {
        mcpOperationService.deleteMcpServer(id);
    }

    public ToolRegistryEntry enableMcpServer(String id) {
        return mcpOperationService.enableMcpServer(id);
    }

    public ToolRegistryEntry disableMcpServer(String id) {
        return mcpOperationService.disableMcpServer(id);
    }

    public List<String> listMcpServerTools(String serverId) {
        return mcpOperationService.listMcpServerTools(serverId);
    }

    public List<McpSchema.Tool> listMcpServerToolDetails(String serverId) {
        return mcpOperationService.listMcpServerToolDetails(serverId);
    }

    public McpClientManager.ConnectionState getMcpServerState(String serverId) {
        return mcpOperationService.getMcpServerState(serverId);
    }

    public McpClientManager.ConnectionState connectMcpServer(String serverId) {
        return mcpOperationService.connectMcpServer(serverId);
    }

    public ToolCallResult callMcpServerTool(String serverId, String toolName, String argumentsJson) {
        return mcpOperationService.callMcpServerTool(serverId, toolName, argumentsJson);
    }

    // ── Service API Tools ───────────────────────────────────────────────────────

    public InternalApiToolLoader getInternalApiToolLoader() {
        return internalApiToolLoader;
    }

    public List<InternalApiToolLoader.ApiAppInfo> listServiceApiApps() {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.listApiApps();
    }

    public List<InternalApiToolLoader.ApiServiceInfo> listApiAppServices(String appName) {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.listApiAppServices(appName);
    }

    public ToolCallResult callServiceApiTool(String toolId, String argumentsJson) {
        if (internalApiToolLoader == null) {
            throw new RuntimeException("service API tools are not initialized");
        }
        if (!InternalApiToolLoader.isApiToolId(toolId)) {
            throw new IllegalArgumentException("unsupported service API tool id: " + toolId);
        }
        var tools = internalApiToolLoader.loadByToolId(toolId);
        if (tools.isEmpty()) {
            throw new RuntimeException("service API tool not found, id=" + toolId);
        }
        if (tools.size() != 1) {
            throw new IllegalArgumentException("test requires a single service API operation, id=" + toolId);
        }
        var payload = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        return tools.getFirst().execute(payload);
    }

    public void reloadApiTools() {
        resolutionService.reloadApiTools();
    }

    // ── Inner types ─────────────────────────────────────────────────────────────

    public record BuiltinToolInfo(String name, String description, String inputSchema) {
    }
}
