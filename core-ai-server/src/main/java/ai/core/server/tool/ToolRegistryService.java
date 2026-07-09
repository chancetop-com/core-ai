package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.tool.mcp.McpToolProvider;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolProvider.RefreshPolicy;
import ai.core.mcp.client.McpServerConfig;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.sandbox.SandboxService;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.BuiltinToolProvider;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.util.StopWatch;
import core.framework.web.exception.ConflictException;
import io.modelcontextprotocol.spec.McpSchema;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * @author stephen
 */
public class ToolRegistryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryService.class);
    private static final String CONFIG_PREFIX = "config:";
    private static final String BUILTIN_PREFIX = "builtin:";
    private static final String API_TOOL_ID = "builtin-service-api";

    // Virtual-thread executor for async MCP connect operations.
    // Connection tasks are I/O-bound (HTTP calls to upstream MCP servers / sandbox runtime),
    // so virtual threads let us block without consuming a carrier (platform) thread.
    private static final ExecutorService MCP_CONNECT_EXECUTOR = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("mcp-connect-", 0).factory()
    );

    // Maximum wall-clock time a single connectMcpServer call may wait for the MCP server
    // to finish its initialization handshake before the HTTP response is returned.
    // If the server is still initialising after this period the response carries the
    // current (CONNECTING / NOT_CONNECTED) state, and initialisation proceeds in the
    // background.  The frontend can poll GET …/status for completion.
    private static final Duration CONNECT_OPERATION_TIMEOUT = Duration.ofSeconds(15);

    private final Map<String, ToolRegistryEntry> tools = new ConcurrentHashMap<>();
    private final Map<String, List<ToolCall>> dynamicToolSets = new ConcurrentHashMap<>();
    private final McpServerConnectionManager mcpConnectionManager;
    private ToolRefResolver toolRefResolver;

    // Cache for MCP server tool details to avoid repeated connections during batch polling
    private static final long TOOL_DETAILS_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private final Map<String, CachedToolDetails> toolDetailsCache = new ConcurrentHashMap<>();

    // Cache for MCP server state to reduce contention during batch status polling
    private static final long STATUS_CACHE_TTL_NANOS = Duration.ofSeconds(2).toNanos();
    private final Map<String, CachedState> stateCache = new ConcurrentHashMap<>();

    private record CachedToolDetails(List<McpSchema.Tool> tools, long createdAtNanos) {}
    private record CachedState(McpClientManager.ConnectionState state, long createdAtNanos) {}

    @Inject
    MongoCollection<ToolRegistryEntry> toolRegistryCollection;

    @Inject
    ApiDefinitionService apiDefinitionService;

    private SandboxService sandboxService;

    private InternalApiToolLoader internalApiToolLoader;

    public ToolRegistryService() {
        this.mcpConnectionManager = new McpServerConnectionManager(null);
    }

    public void setSandboxService(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
        this.mcpConnectionManager.setSandboxService(sandboxService);
    }

    public void initialize(String mcpServersJson) {
        loadBuiltinTools();
        loadServiceApiTools();
        loadConfigMcpServers(mcpServersJson);
        loadDatabaseTools();
        toolRefResolver = new ToolRefResolver(tools, internalApiToolLoader, dynamicToolSets);
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
        for (var entry : BuiltinTools.GROUPED_SETS.entrySet()) {
            var registry = new ToolRegistryEntry();
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
                var registry = new ToolRegistryEntry();
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
            if (entry.type == ToolType.MCP) mcpConnectionManager.registerMcpServer(entry);
        }
    }

    public void syncDatabaseTools() {
        var dbEntries = toolRegistryCollection.find(Filters.eq("enabled", true));
        var dbEntryIds = new java.util.HashSet<String>();

        for (var dbEntry : dbEntries) {
            if (dbEntry.type != ToolType.MCP) continue;
            dbEntryIds.add(dbEntry.id);

            var memEntry = tools.get(dbEntry.id);
            if (memEntry == null) {
                tools.put(dbEntry.id, dbEntry);
                if (isSandboxHosted(dbEntry)) {
                    mcpConnectionManager.ensureRegisteredOnDiscovery(dbEntry);
                } else {
                    mcpConnectionManager.registerMcpServer(dbEntry);
                    mcpConnectionManager.warmupMcpServer(dbEntry.id);
                }
                LOGGER.info("synced new mcp server from db, id={}, name={}", dbEntry.id, dbEntry.name);
            } else if (!dbEntry.enabled.equals(memEntry.enabled) || !dbEntry.config.equals(memEntry.config)) {
                tools.put(dbEntry.id, dbEntry);
                mcpConnectionManager.applyMcpServerState(dbEntry, !dbEntry.config.equals(memEntry.config));
                if (isSandboxHosted(dbEntry) && dbEntry.enabled) {
                    mcpConnectionManager.ensureRegisteredOnDiscovery(dbEntry);
                }
                LOGGER.info("synced updated mcp server from db, id={}, name={}", dbEntry.id, dbEntry.name);
            }
        }

        var toRemove = new java.util.ArrayList<String>();
        for (var id : tools.keySet()) {
            var entry = tools.get(id);
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

    /**
     * Registers a builtin tool group so it appears in the tool registry UI and can be
     * dynamically configured for agents. Both stores the tools in dynamicToolSets for
     * resolution and creates a {@link ToolRegistryEntry} in the tools map for visibility.
     */
    public void registerBuiltinToolGroup(String name, String category, String description, List<ToolCall> toolCalls) {
        dynamicToolSets.put(name, toolCalls);
        var registry = new ToolRegistryEntry();
        registry.id = name;
        registry.name = name.substring(BUILTIN_PREFIX.length());
        registry.type = ToolType.BUILTIN;
        registry.category = category;
        registry.enabled = true;
        registry.description = description;
        registry.config = Map.of();
        registry.createdAt = ZonedDateTime.now();
        tools.put(registry.id, registry);
        LOGGER.info("registered builtin tool group, name={}, category={}, tools={}", name, category, toolCalls.size());
    }

    public ToolRegistryEntry createMcpServer(String name, String description, String category, Map<String, String> config, Boolean enabled) {
        return createMcpServerInternal(name, description, category, config, enabled, null);
    }

    private ToolRegistryEntry createMcpServerInternal(String name, String description, String category,
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
        if (entity.enabled) {
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
     * Each server is automatically configured as sandbox-hosted.
     */
    @SuppressWarnings("unchecked")
    public List<ToolRegistryEntry> importMcpServers(String rawJson, String category, Boolean enabled) {
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
            // Preserve the original JSON so users can review what they pasted.
            var rawConfig = JsonUtil.toJson(serverConf);
            var entity = createMcpServerInternal(name, null, category, config, enabled, rawConfig);
            results.add(entity);
        }
        LOGGER.info("imported {} mcp servers, category={}, enabled={}", results.size(), category, enabled);
        return results;
    }

    // Convert a single server config from the import JSON to Map<String,String>
    // suitable for ToolRegistryEntry.config storage. Container types (args/env/headers)
    // are serialized as JSON so they can be parsed back by McpServerConfig helpers.
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
                // Map / List / other nested structures — store as JSON so downstream
                // parsers (McpServerConfig.parseEnv / parseArgsList / parseHeadersJson) round-trip cleanly.
                result.put(e.getKey(), JsonUtil.toJson(value));
            }
        }
        return result;
    }

    public ToolRegistryEntry updateMcpServer(String id, String name, String description, String category, Map<String, String> config, Boolean enabled, String rawConfig) {
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

    public void deleteMcpServer(String id) {
        if (id.startsWith(CONFIG_PREFIX)) throw new RuntimeException("cannot delete mcp server from configuration");
        if (id.startsWith(BUILTIN_PREFIX)) throw new RuntimeException("cannot delete builtin tool set");

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

    public ToolRegistryEntry enableMcpServer(String id) {
        invalidateMcpServerCache(id);
        if (id.startsWith(CONFIG_PREFIX)) throw new RuntimeException("cannot enable/disable mcp server from configuration");
        if (id.startsWith(BUILTIN_PREFIX)) throw new RuntimeException("cannot enable/disable builtin tool set");

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

    public ToolRegistryEntry disableMcpServer(String id) {
        invalidateMcpServerCache(id);
        if (id.startsWith(CONFIG_PREFIX)) throw new RuntimeException("cannot enable/disable mcp server from configuration");
        if (id.startsWith(BUILTIN_PREFIX)) throw new RuntimeException("cannot enable/disable builtin tool set");

        var entity = tools.get(id);
        if (entity == null) throw new RuntimeException("mcp server not found, id=" + id);
        if (entity.type != ToolType.MCP) throw new RuntimeException("tool is not an mcp server, id=" + id);

        entity.enabled = false;
        toolRegistryCollection.replace(entity);
        mcpConnectionManager.unregisterMcpServer(id);
        LOGGER.info("disabled mcp server, id={}, name={}", entity.id, entity.name);
        return entity;
    }

    public List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs) {
        return resolveToolRefs(toolRefs, null);
    }

    /**
     * Resolve tool refs in the context of a specific session.
     * <p>
     * For SANDBOX_HOSTED MCP refs, this materializes the session sandbox (if not
     * already ready), starts the MCP child process inside it, and registers the
     * client in the session-scoped McpClientManager — so concurrent sessions don't
     * collide on shared server ids. Non-sandbox MCP refs use the global manager.
     * <p>
     * The session's sandbox must already exist (via {@code sandboxService.createSandbox})
     * before calling this. If it doesn't, sandbox-hosted refs fall back to the global
     * manager, which won't have them registered (intentionally) — those tools will be
     * unavailable until either this is re-called after the sandbox exists, or the
     * caller invokes {@code AgentSessionManager.loadToolRefs} at runtime.
     *
     * @param sessionId the session/run id; pass {@code null} for non-session callers
     *                  (LLMCallBuilderTools, AgentBuilderTools, etc.).
     */
    public List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs, String sessionId) {
        if (toolRefResolver == null) return List.of();
        McpClientManager sessionMgr = null;
        if (sessionId != null && sandboxService != null && toolRefs != null && !toolRefs.isEmpty()) {
            var sandbox = sandboxService.getSandbox(sessionId);
            if (sandbox != null) {
                sessionMgr = prepareSessionMcpServers(toolRefs, sessionId, sandbox);
            }
        }
        return toolRefResolver.resolve(toolRefs, sessionMgr);
    }

    /**
     * Resolves {@link ToolRef}s into a core {@link ToolRegistry}
     * populated with appropriate {@link ai.core.tool.registry.ToolProvider}s.
     * <p>
     * Each tool source (BUILTIN, MCP, API) is registered as a separate provider so the
     * registry can apply priority-based dedup and respect {@code ToolExposure}.
     * <p>
     * MCP sandbox isolation is handled per-session: sandbox-hosted MCP refs are prepared
     * via {@link #prepareSessionMcpServers} and registered with a session-scoped manager.
     *
     * @param toolRefs the tool references to resolve
     * @param sessionId the session/run id, or {@code null} for non-session callers
     * @return a core ToolRegistry with providers registered for each resolved tool source
     */
    public ToolRegistry resolveToToolRegistry(List<ToolRef> toolRefs, String sessionId) {
        var registry = ToolRegistryFactory.createEmpty();
        if (toolRefs == null || toolRefs.isEmpty()) return registry;

        McpClientManager sessionMgr = null;
        if (sessionId != null && sandboxService != null) {
            var sandbox = sandboxService.getSandbox(sessionId);
            if (sandbox != null) {
                sessionMgr = prepareSessionMcpServers(toolRefs, sessionId, sandbox);
            }
        }

        for (var ref : toolRefs) {
            if (ref == null || ref.id == null) continue;
            var type = effectiveType(ref);
            if (type != null) {
                switch (type) {
                    case BUILTIN -> registerBuiltinProvider(registry, ref);
                    case MCP -> registerMcpProvider(registry, ref, sessionMgr);
                    case API -> registerApiProvider(registry, ref);
                    case AGENT -> LOGGER.debug("skipping AGENT tool ref at registry level, id={}", ref.id);
                    default -> LOGGER.warn("unknown tool source type, id={}, type={}", ref.id, type);
                }
            } else {
                registerLegacyProvider(registry, ref, sessionMgr);
            }
        }
        return registry;
    }

    private ToolSourceType effectiveType(ToolRef ref) {
        var entry = lookupToolEntry(ref.id);
        return entry != null ? entryType(entry) : ref.type;
    }

    private ToolRegistryEntry lookupToolEntry(String id) {
        var entry = tools.get(id);
        if (entry == null) entry = tools.get("builtin:" + id);
        return entry;
    }

    private static ToolSourceType entryType(ToolRegistryEntry entry) {
        return switch (entry.type) {
            case BUILTIN -> ToolSourceType.BUILTIN;
            case MCP -> ToolSourceType.MCP;
            case API -> ToolSourceType.API;
        };
    }

    private void registerBuiltinProvider(ToolRegistry registry, ToolRef ref) {
        var entry = lookupToolEntry(ref.id);
        if (entry != null && entry.type == ToolType.BUILTIN) {
            var setName = entry.config != null ? entry.config.get("set") : null;
            if (setName != null) {
                registry.registerProvider(BuiltinToolProvider.fromSet(setName));
                return;
            }
        }
        // fallback for dynamically registered builtin tool sets
        var dynamicSet = dynamicToolSets.get(ref.id);
        if (dynamicSet != null) {
            registry.registerProvider(new ListToolProvider("dynamic:" + ref.id, dynamicSet));
        }
    }

    @SuppressWarnings("checkstyle:NestedIfDepth")
    private void registerMcpProvider(ToolRegistry registry, ToolRef ref,
                                     McpClientManager sessionMgr) {
        var parsed = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (parsed != null) {
            var refServerName = parsed.serverId();
            if (refServerName != null) {
                var name = resolveMcpServerName(refServerName);
                var includes = parsed.toolName() != null ? List.of(parsed.toolName()) : null;
                registerMcpByName(registry, name, includes, sessionMgr);
            }
            return;
        }

        var entry = tools.get(ref.id);
        var name = entry != null ? resolveMcpServerName(entry.id) : null;
        if (name == null) {
            name = ref.source != null ? ref.source : ref.id;
        }
        registerMcpByName(registry, name, null, sessionMgr);
    }

    private void registerMcpByName(ToolRegistry registry, String lookupKey, List<String> includes,
                                    McpClientManager sessionMgr) {
        var mgr = pickMcpManager(lookupKey, sessionMgr);
        if (mgr == null || !mgr.hasServer(lookupKey)) return;
        var sandbox = sessionMgr != null && sessionMgr.hasServer(lookupKey);
        var entry = findMcpEntryByLookupKey(lookupKey);
        var serverName = entry != null ? entry.name : lookupKey;
        registry.registerProvider(new McpToolProvider(lookupKey, serverName, mgr, includes, sandbox ? RefreshPolicy.MANUAL : RefreshPolicy.EVERY_TURN));
    }

    private ToolRegistryEntry findMcpEntryByLookupKey(String lookupKey) {
        var entry = tools.get(lookupKey);
        if (entry != null && entry.type == ToolType.MCP) return entry;
        entry = tools.get(CONFIG_PREFIX + lookupKey);
        if (entry != null && entry.type == ToolType.MCP) return entry;
        for (var e : tools.values()) {
            if (e.type != ToolType.MCP) continue;
            if (lookupKey.equals(e.name)) return e;
            if (lookupKey.equals(resolveMcpServerName(e.id))) return e;
        }
        return null;
    }

    private void registerApiProvider(ToolRegistry registry, ToolRef ref) {
        List<ToolCall> tools = List.of();
        if (internalApiToolLoader != null && InternalApiToolLoader.isApiToolId(ref.id)) {
            tools = internalApiToolLoader.loadByToolId(ref.id);
        }
        if (tools.isEmpty() && internalApiToolLoader != null && ref.source != null) {
            tools = internalApiToolLoader.loadApiAppTools(ref.source);
        }
        if (!tools.isEmpty()) {
            registry.registerProvider(new ListToolProvider(ToolProvider.API_TOOLS + ":" + ref.id, tools));
        }
    }

    private void registerLegacyProvider(ToolRegistry registry, ToolRef ref,
                                        McpClientManager sessionMgr) {
        var entry = lookupToolEntry(ref.id);
        if (entry == null) return;
        switch (entry.type) {
            case MCP -> registerMcpProvider(registry, ref, sessionMgr);
            case BUILTIN -> registerBuiltinProvider(registry, ref);
            case API -> registerApiProvider(registry, ref);
            default -> LOGGER.warn("unknown tool type in legacy ref, id={}, type={}", ref.id, entry.type);
        }
    }

    private static McpClientManager pickMcpManager(String serverName, McpClientManager sessionMgr) {
        if (sessionMgr != null && serverName != null && sessionMgr.hasServer(serverName)) return sessionMgr;
        return McpClientManagerRegistry.getManager();
    }

    private static String resolveMcpServerName(String name) {
        if (name.startsWith(CONFIG_PREFIX)) {
            return name.substring(CONFIG_PREFIX.length());
        }
        // also resolve through entry to find the manager-recognizable name
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager != null && mcpManager.hasServer(name)) return name;
        return name;
    }

    private static String resolveMcpServerName(ToolRegistryEntry entry) {
        return resolveMcpServerName(entry.id);
    }

    // Walk the refs, find sandbox-hosted MCP entries, and ensure each is registered
    // in the session's McpClientManager (starts the MCP child process on the session
    // sandbox the first time). Returns the session manager if any were registered,
    // else null so the resolver falls through to the global manager.
    // <p>
    // Uses a shorter per-server timeout (SESSION_MCP_STARTUP_TIMEOUT_SECONDS) so a
    // slow/failing MCP server does not block session creation for minutes. If a server
    // fails to start it is simply skipped — the session can still proceed without it.
    // Multiple servers are started in parallel to minimise the overall delay.
    private McpClientManager prepareSessionMcpServers(List<ToolRef> toolRefs, String sessionId, Sandbox sandbox) {
        var sandboxHostedEntries = collectSandboxHostedEntries(toolRefs);
        if (sandboxHostedEntries.isEmpty()) return null;

        // MCP child processes need the sandbox's ip/port — force LazySandbox materialization.
        if (sandboxService != null) sandboxService.ensureSandboxReady(sessionId);

        var sessionMgr = sandboxService != null ? sandboxService.getOrCreateSessionMcpManager(sessionId) : null;
        if (sessionMgr == null) return null;

        var startupTimeout = SandboxConstants.SESSION_MCP_STARTUP_TIMEOUT_SECONDS;

        // Start MCP servers in parallel so multiple slow servers don't compound sequentially.
        sandboxHostedEntries.parallelStream().forEach(entry -> {
            try {
                var registered = mcpConnectionManager.registerOnSession(entry, sessionMgr, sandbox, startupTimeout);
                if (registered && sandboxService != null) {
                    sandboxService.recordSessionMcpServer(sessionId, entry.id);
                }
            } catch (Exception e) {
                LOGGER.warn("failed to start sandbox-hosted mcp server during session creation, id={}, name={}: {}",
                        entry.id, entry.name, e.getMessage());
            }
        });

        return sessionMgr;
    }

    private List<ToolRegistryEntry> collectSandboxHostedEntries(List<ToolRef> toolRefs) {
        var seen = new java.util.LinkedHashSet<String>();
        var result = new ArrayList<ToolRegistryEntry>();
        for (var ref : toolRefs) {
            if (ref == null || ref.id == null) continue;
            var entry = findMcpEntryForRef(ref);
            if (entry != null && "sandbox_hosted".equalsIgnoreCase(entry.config.get("transport")) && seen.add(entry.id)) {
                result.add(entry);
            }
        }
        return result;
    }

    private ToolRegistryEntry findMcpEntryForRef(ToolRef ref) {
        var entry = tools.get(ref.id);
        if (entry != null && entry.type == ToolType.MCP) return entry;
        // mcp-tool:<server>:<tool> or mcp-tool:<tool> with source=server — resolve to the server entry
        var parsed = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (parsed != null && parsed.serverId() != null) {
            var serverEntry = tools.get(parsed.serverId());
            if (serverEntry != null && serverEntry.type == ToolType.MCP) return serverEntry;
        }
        // ref.source can also point at the entry id (e.g. config:<name>)
        if (ref.source != null) {
            var srcEntry = tools.get(ref.source);
            if (srcEntry != null && srcEntry.type == ToolType.MCP) return srcEntry;
        }
        return null;
    }

    public List<String> extractAgentIds(List<ToolRef> toolRefs) {
        if (toolRefs == null || toolRefs.isEmpty()) return List.of();
        return toolRefs.stream()
                .filter(ref -> ref.type == ToolSourceType.AGENT)
                .map(ref -> ref.id)
                .toList();
    }

    public InternalApiToolLoader getInternalApiToolLoader() {
        return internalApiToolLoader;
    }

    public List<InternalApiToolLoader.ApiAppInfo> listServiceApiApps() {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.listApiApps();
    }

    public List<InternalApiToolLoader.ApiServiceInfo> listApiAppServices(String appName) {
        return internalApiToolLoader == null ? List.of() : internalApiToolLoader.listApiAppServices(appName);
    }

    public List<String> listMcpServerTools(String serverId) {
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

    public List<McpSchema.Tool> listMcpServerToolDetails(String serverId) {
        var watch = new StopWatch();
        var entity = requireMcpEntity(serverId);

        // Check cache first for batch-polling scenarios
        var cached = toolDetailsCache.get(serverId);
        if (cached != null && System.nanoTime() - cached.createdAtNanos() < TOOL_DETAILS_CACHE_TTL_NANOS) {
            LOGGER.debug("listMcpServerToolDetails cache hit, id={}", serverId);
            return cached.tools();
        }

        if (isSandboxHosted(entity)) {
            // Tool browsing for a sandbox-hosted server: lazily start on discovery.
            mcpConnectionManager.ensureRegisteredOnDiscovery(entity);
        }

        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null || !mcpManager.hasServer(entity.id)) {
            toolDetailsCache.put(serverId, new CachedToolDetails(List.of(), System.nanoTime()));
            return List.of();
        }

        // Short-circuit if server is in FAILED or RECONNECTING state — avoid blocking on connection attempts
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

    private static boolean isSandboxHosted(ToolRegistryEntry entity) {
        return entity.config != null && "sandbox_hosted".equalsIgnoreCase(entity.config.get("transport"));
    }

    private java.util.Optional<ToolRegistryEntry> findMcpServerByName(String name) {
        return toolRegistryCollection.findOne(Filters.and(
                Filters.eq("type", ToolType.MCP.name()),
                Filters.eq("name", name)));
    }

    public McpClientManager.ConnectionState getMcpServerState(String serverId) {
        // Short-term cache to avoid redundant lookups during batch polling
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

    public McpClientManager.ConnectionState connectMcpServer(String serverId) {
        invalidateMcpServerCache(serverId);
        var entity = requireMcpEntity(serverId);
        if (!Boolean.TRUE.equals(entity.enabled)) throw new RuntimeException("mcp server is disabled, id=" + serverId);

        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null) throw new RuntimeException("mcp manager not initialized");

        // Fast-fail: don't attempt connection if the server is already in a bad state.
        var currentState = mcpManager.getState(entity.id);
        if (currentState == McpClientManager.ConnectionState.FAILED
            || currentState == McpClientManager.ConnectionState.RECONNECTING) {
            LOGGER.warn("mcp server is in {} state, skipping connect, id={}", currentState, serverId);
            return currentState;
        }
        if (currentState == McpClientManager.ConnectionState.CONNECTED) return currentState;

        // ── Register / ensure the server is known to the manager ──────────────
        // For non-sandbox servers registration is a fast in-memory operation; for
        // sandbox-hosted servers ensureRegisteredOnDiscovery may POST to the sandbox
        // runtime (potentially slow), so it runs inside the async task below.
        if (!isSandboxHosted(entity)) {
            mcpConnectionManager.registerMcpServer(entity);
        }

        // Re-read state after possible registration
        currentState = mcpManager.getState(entity.id);
        if (currentState == McpClientManager.ConnectionState.CONNECTED) return currentState;
        if (currentState == McpClientManager.ConnectionState.CONNECTING) {
            // Another request already started connecting — return immediately.
            return currentState;
        }

        // ── Submit the actual connection work to a virtual-thread executor ────
        // The HTTP response thread returns as soon as the background task completes
        // OR the CONNECT_OPERATION_TIMEOUT elapses, whichever comes first.
        // If the timeout fires first the connection continues in the background.
        Future<McpClientManager.ConnectionState> future = MCP_CONNECT_EXECUTOR.submit(() -> {
            try {
                // Sandbox-hosted servers need the sandbox MCP process started first.
                // This is done here (inside the async task) because it may block for
                // up to MCP_STARTUP_TIMEOUT_SECONDS (180 s).
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
            // The connection is still in progress — don't cancel it, let it finish
            // in the background.  Return the current state so the caller (typically
            // the frontend) can poll GET …/status for completion.
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

    private void invalidateMcpServerCache(String serverId) {
        toolDetailsCache.remove(serverId);
        stateCache.remove(serverId);
    }

    public ToolCallResult callMcpServerTool(String serverId, String toolName, String argumentsJson) {
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

    private ToolRegistryEntry requireMcpEntity(String serverId) {
        var entity = tools.get(serverId);
        if (entity == null || entity.type != ToolType.MCP) {
            throw new RuntimeException("mcp server not found or not MCP type, id=" + serverId);
        }
        return entity;
    }

    public void reloadApiTools() {
        if (toolRefResolver != null) toolRefResolver.reloadApiTools();
    }
}
