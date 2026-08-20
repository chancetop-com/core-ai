package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.media.MediaProvider;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.llmcall.LLMCallTool;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.UnderstandVideoTool;
import ai.core.tool.registry.BuiltinToolProvider;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.tool.github.GitHubTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves ToolRef instances to ToolCall instances.
 * Handles BUILTIN, MCP, API tool types.
 *
 * @author stephen
 */
public class ToolRefResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRefResolver.class);
    private static final String CONFIG_PREFIX = "config:";
    private static final String API_TOOL_ID = "builtin-service-api";

    static ToolSourceType requireCompatibleType(ToolRef toolRef, ToolSourceType authoritativeType) {
        if (authoritativeType != null && toolRef.type != null && authoritativeType != toolRef.type) {
            throw new IllegalArgumentException("tool reference is unavailable");
        }
        return authoritativeType != null ? authoritativeType : toolRef.type;
    }

    /**
     * Resolves an individual tool inside a dynamically registered builtin group,
     * e.g. "builtin:self-harness:list_agents". Returns an empty list when the id
     * is not an individual group tool ref or when the group or tool is unknown.
     */
    static List<ToolCall> resolveDynamicGroupTool(String id, Map<String, List<ToolCall>> dynamicToolSets) {
        var parsed = ToolRef.parseBuiltinGroupToolId(id);
        if (parsed == null) return List.of();
        var groupTools = dynamicToolSets.get(parsed.groupId());
        if (groupTools == null) return List.of();
        return groupTools.stream().filter(tool -> parsed.toolName().equals(tool.getName())).toList();
    }

    private final Map<String, ToolRegistryEntry> toolRegistry;
    private final InternalApiToolLoader apiToolLoader;
    private final Map<String, List<ToolCall>> dynamicToolSets;
    private final MediaProvider mediaProvider;
    private final GitHubTokenProvider gitHubTokenProvider;
    private final ApplicationMcpManager applicationMcpManager;
    private AgentDefinitionService agentDefinitionService;
    private LLMCallExecutor llmCallExecutor;
    private UnderstandVideoTool.VideoUnderstandingService videoService;
    private java.util.function.UnaryOperator<List<ToolCall>> builtinEnhancer;
    private final Map<String, List<ToolCall>> apiToolCache = new ConcurrentHashMap<>();

    public ToolRefResolver(Map<String, ToolRegistryEntry> toolRegistry, InternalApiToolLoader apiToolLoader,
                           Map<String, List<ToolCall>> dynamicToolSets) {
        this(toolRegistry, apiToolLoader, dynamicToolSets, null, null, null);
    }

    public ToolRefResolver(Map<String, ToolRegistryEntry> toolRegistry, InternalApiToolLoader apiToolLoader,
                             Map<String, List<ToolCall>> dynamicToolSets, MediaProvider mediaProvider,
                             GitHubTokenProvider gitHubTokenProvider, ApplicationMcpManager applicationMcpManager) {
        this.toolRegistry = toolRegistry;
        this.apiToolLoader = apiToolLoader;
        this.dynamicToolSets = dynamicToolSets;
        this.mediaProvider = mediaProvider;
        this.gitHubTokenProvider = gitHubTokenProvider;
        this.applicationMcpManager = applicationMcpManager;
    }

    public void setVideoService(UnderstandVideoTool.VideoUnderstandingService videoService) {
        this.videoService = videoService;
    }

    public void setAgentDefinitionService(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    public void setLlmCallExecutor(LLMCallExecutor llmCallExecutor) {
        this.llmCallExecutor = llmCallExecutor;
    }

    /**
     * Optional transformation applied to builtin tool lists before registration, e.g. to inject
     * a gateway-aware description into generate_video.
     */
    public void setBuiltinEnhancer(java.util.function.UnaryOperator<List<ToolCall>> builtinEnhancer) {
        this.builtinEnhancer = builtinEnhancer;
    }

    private List<ToolCall> enhanceBuiltinTools(List<ToolCall> tools) {
        return builtinEnhancer == null ? tools : builtinEnhancer.apply(tools);
    }

    public List<ToolCall> resolve(List<ToolRef> toolRefs) {
        return resolve(toolRefs, null, null);
    }

    /**
     * Resolve refs with an optional session-scoped MCP manager.
     * <p>
     * Sandbox-hosted MCP servers register themselves in this session manager before
     * resolve() is called (see ToolRegistryService.prepareSessionMcpServers). For
     * each MCP ref we prefer this session manager over the global one when it
     * contains the target server — so the returned McpToolCall is bound to the
     * session's sandbox bridge, isolating concurrent sessions from each other.
     */
    public List<ToolCall> resolve(List<ToolRef> toolRefs, McpClientManager sessionMcpManager) {
        return resolve(toolRefs, sessionMcpManager, null);
    }

    public List<ToolCall> resolve(List<ToolRef> toolRefs, McpClientManager sessionMcpManager,
                                  String callerUserId) {
        if (toolRefs == null || toolRefs.isEmpty()) return List.of();

        var result = new ArrayList<ToolCall>();
        for (var toolRef : toolRefs) {
            if (toolRef == null) continue;
            if (AgentDependencyAccessPolicy.isLlmCallRef(toolRef)) {
                resolveLLMCallRef(toolRef, result, callerUserId);
                continue;
            }
            if (toolRef.id == null) continue;

            var type = effectiveType(toolRef);
            if (type != null) {
                switch (type) {
                    case BUILTIN -> resolveBuiltinRef(toolRef, result);
                    case MCP -> resolveMcpRef(toolRef, result, sessionMcpManager);
                    case API -> resolveApiRef(toolRef, result);
                    case AGENT -> LOGGER.debug("skipping AGENT tool ref at registry level, id={}", toolRef.id);
                    case LLM_CALL -> resolveLLMCallRef(toolRef, result, callerUserId);
                    default -> LOGGER.warn("unknown tool type, id={}, type={}", toolRef.id, type);
                }
            } else {
                resolveLegacyRef(toolRef, result, sessionMcpManager);
            }
        }
        return result;
    }

    private ToolSourceType effectiveType(ToolRef toolRef) {
        var entryType = registryType(toolRef.id);
        return requireCompatibleType(toolRef, entryType);
    }

    private ToolSourceType registryType(String id) {
        var entry = toolRegistry.get(id);
        if (entry == null) entry = toolRegistry.get("builtin:" + id);
        if (entry == null) return null;
        return switch (entry.type) {
            case BUILTIN -> ToolSourceType.BUILTIN;
            case MCP -> ToolSourceType.MCP;
            case API -> ToolSourceType.API;
        };
    }

    private void resolveBuiltinRef(ToolRef toolRef, List<ToolCall> result) {
        var entry = lookupBuiltinEntry(toolRef.id);
        if (entry != null) {
            var setName = entry.config != null ? entry.config.get("set") : null;
            if (setName != null) {
                var provider = BuiltinToolProvider.fromSet(setName, mediaProvider, gitHubTokenProvider, videoService, this::enhanceBuiltinTools);
                result.addAll(provider.provide().values());
            }
            return;
        }
        // fallback for dynamically registered builtin tool sets
        var dynamicTools = dynamicToolSets.get(toolRef.id);
        if (dynamicTools != null) {
            result.addAll(dynamicTools);
            return;
        }
        // individual tool inside a dynamic builtin group, e.g. "builtin:self-harness:list_agents"
        result.addAll(resolveDynamicGroupTool(toolRef.id, dynamicToolSets));
    }

    private ToolRegistryEntry lookupBuiltinEntry(String id) {
        var entry = toolRegistry.get(id);
        if (entry != null && entry.type == ToolType.BUILTIN) return entry;
        // builtin tools are registered with "builtin:" prefix, e.g. "builtin:builtin-all"
        entry = toolRegistry.get("builtin:" + id);
        if (entry != null && entry.type == ToolType.BUILTIN) return entry;
        return null;
    }

    private void resolveMcpRef(ToolRef toolRef, List<ToolCall> result, McpClientManager sessionMgr) {
        var parsed = ToolRef.parseMcpToolId(toolRef.id, toolRef.source);
        if (parsed != null) {
            resolveMcpToolRef(parsed, toolRef, result, sessionMgr);
            return;
        }

        var entry = toolRegistry.get(toolRef.id);
        if (entry != null) {
            result.addAll(resolveMcpTools(entry, sessionMgr));
            return;
        }

        var serverName = toolRef.source != null ? toolRef.source : toolRef.id;
        var mgr = pickManager(serverName, sessionMgr);
        if (mgr != null && mgr.hasServer(serverName)) {
            result.addAll(loadMcpToolsSafe(mgr, serverName));
        }
    }

    private void resolveMcpToolRef(ToolRef.McpToolId parsed, ToolRef toolRef,
                                    List<ToolCall> result, McpClientManager sessionMgr) {
        var serverId = parsed.serverId();
        if (serverId == null) {
            LOGGER.warn("unable to resolve individual mcp tool, id={}, source={}", toolRef.id, toolRef.source);
            return;
        }
        var toolName = parsed.toolName();
        var mgr = pickManager(serverId, sessionMgr);
        if (mgr != null && mgr.hasServer(serverId)) {
            result.addAll(loadMcpToolSafe(mgr, serverId, toolName));
            return;
        }
        resolveMcpToolFallback(serverId, toolName, toolRef, result, sessionMgr);
    }

    private void resolveMcpToolFallback(String serverId, String toolName, ToolRef toolRef,
                                         List<ToolCall> result, McpClientManager sessionMgr) {
        var entry = toolRegistry.get(serverId);
        if (entry == null) {
            LOGGER.warn("unable to resolve individual mcp tool, id={}, source={}", toolRef.id, toolRef.source);
            return;
        }
        var resolvedServerName = resolveMcpServerName(entry);
        if (resolvedServerName == null) {
            LOGGER.warn("unable to resolve individual mcp tool, id={}, source={}", toolRef.id, toolRef.source);
            return;
        }
        var fallbackMgr = pickManager(resolvedServerName, sessionMgr);
        if (fallbackMgr != null && fallbackMgr.hasServer(resolvedServerName)) {
            result.addAll(loadMcpToolSafe(fallbackMgr, resolvedServerName, toolName));
        } else {
            LOGGER.warn("unable to resolve individual mcp tool, id={}, source={}", toolRef.id, toolRef.source);
        }
    }

    // Prefer the session manager when it has the requested server (i.e. the session
    // has adopted a sandbox-hosted MCP). Fall back to the global manager for normal
    // STDIO/HTTP servers and for any sandbox-hosted server not in this session.
    private McpClientManager pickManager(String serverName, McpClientManager sessionMgr) {
        if (sessionMgr != null && serverName != null && sessionMgr.hasServer(serverName)) return sessionMgr;
        return applicationMcpManager != null ? applicationMcpManager.get() : null;
    }

    private void resolveApiRef(ToolRef toolRef, List<ToolCall> result) {
        var entry = toolRegistry.get(toolRef.id);
        if (entry != null) {
            result.addAll(resolveApiTools(entry));
            return;
        }

        if (apiToolLoader != null && InternalApiToolLoader.isApiToolId(toolRef.id)) {
            result.addAll(apiToolLoader.loadByToolId(toolRef.id));
            return;
        }

        if (apiToolLoader != null && toolRef.source != null) {
            result.addAll(apiToolLoader.loadApiAppTools(toolRef.source));
        }
    }

    /**
     * Resolves an LLM_CALL ref by loading its definition and wrapping it as a callable tool.
     * Unlike AGENT refs (which are skipped and assembled separately), a missing or invalid
     * LLM_CALL definition fails fast — an agent that references a deleted llm-call tool
     * cannot degrade gracefully anyway.
     */
    private void resolveLLMCallRef(ToolRef toolRef, List<ToolCall> result, String callerUserId) {
        var definitionId = AgentDependencyAccessPolicy.requireLlmCallDefinitionId(toolRef);
        AgentDependencyAccessPolicy.requireLlmCallCaller(callerUserId);
        if (agentDefinitionService == null) {
            throw new IllegalStateException("LLM_CALL tool resolution requires AgentDefinitionService");
        }
        var definition = agentDefinitionService.resolveLlmCallToolDefinition(definitionId, callerUserId);
        if (llmCallExecutor == null) {
            throw new IllegalStateException("LLM_CALL tool resolution requires LLMCallExecutor");
        }
        result.add(LLMCallTool.create(definition, llmCallExecutor));
    }

    private void resolveLegacyRef(ToolRef toolRef, List<ToolCall> result, McpClientManager sessionMgr) {
        var entry = toolRegistry.get(toolRef.id);
        if (entry == null) {
            // builtin tools are registered with "builtin:" prefix, e.g. "builtin:builtin-all"
            entry = toolRegistry.get("builtin:" + toolRef.id);
        }
        if (entry == null) return;

        switch (entry.type) {
            case MCP -> result.addAll(resolveMcpTools(entry, sessionMgr));
            case BUILTIN -> {
                var setName = entry.config != null ? entry.config.get("set") : null;
                var provider = BuiltinToolProvider.fromSet(setName, mediaProvider, gitHubTokenProvider, videoService, this::enhanceBuiltinTools);
                result.addAll(provider.provide().values());
            }
            case API -> result.addAll(resolveApiTools(entry));
            default -> LOGGER.warn("unknown tool type in legacy ref, id={}, type={}", toolRef.id, entry.type);
        }
    }

    private List<ToolCall> resolveMcpTools(ToolRegistryEntry entry, McpClientManager sessionMgr) {
        var byEntryId = pickManager(entry.id, sessionMgr);
        if (byEntryId != null && byEntryId.hasServer(entry.id)) {
            return loadMcpToolsSafe(byEntryId, entry.id);
        }
        if (entry.id.startsWith(CONFIG_PREFIX)) {
            var serverName = entry.id.substring(CONFIG_PREFIX.length());
            var byShortName = pickManager(serverName, sessionMgr);
            if (byShortName != null && byShortName.hasServer(serverName)) {
                return loadMcpToolsSafe(byShortName, serverName);
            }
        }
        return List.of();
    }

    private List<ToolCall> loadMcpToolsSafe(McpClientManager mcpManager, String serverName) {
        try {
            return new ArrayList<>(McpToolCalls.from(mcpManager, List.of(serverName), null));
        } catch (Exception e) {
            LOGGER.warn("skip MCP server {} due to load failure: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    private List<ToolCall> loadMcpToolSafe(McpClientManager mcpManager, String serverName, String toolName) {
        try {
            return new ArrayList<>(McpToolCalls.from(mcpManager, List.of(serverName), List.of(toolName)));
        } catch (Exception e) {
            LOGGER.warn("skip MCP tool {}/{} due to load failure: {}", serverName, toolName, e.getMessage());
            return List.of();
        }
    }

    /** Resolve the server name used by McpClientManager from a ToolRegistryEntry entry. */
    private String resolveMcpServerName(ToolRegistryEntry entry) {
        if (entry.id.startsWith(CONFIG_PREFIX)) {
            return entry.id.substring(CONFIG_PREFIX.length());
        }
        var mcpManager = applicationMcpManager != null ? applicationMcpManager.get() : null;
        if (mcpManager != null && mcpManager.hasServer(entry.id)) {
            return entry.id;
        }
        return null;
    }

    private List<ToolCall> resolveApiTools(ToolRegistryEntry entry) {
        if (API_TOOL_ID.equals(entry.id)) {
            var cached = apiToolCache.get(entry.id);
            if (cached != null) return cached;
        }

        if (apiToolLoader != null && entry.id.startsWith("api-app:")) {
            var appName = entry.id.substring("api-app:".length());
            return apiToolLoader.loadApiAppTools(appName);
        }

        var cached = apiToolCache.get(entry.id);
        if (cached != null) return cached;

        if (apiToolLoader != null) {
            var apiTools = apiToolLoader.load();
            apiToolCache.put(entry.id, apiTools);
            return apiTools;
        }
        return List.of();
    }

    public void reloadApiTools() {
        if (apiToolLoader == null) {
            LOGGER.warn("InternalApiToolLoader not initialized, skipping reload");
            return;
        }
        try {
            var apiTools = apiToolLoader.load();
            apiToolCache.put(API_TOOL_ID, apiTools);
            LOGGER.info("reloaded {} Service API tools", apiTools.size());
        } catch (Exception e) {
            LOGGER.error("failed to reload Service API tools", e);
        }
    }
}
