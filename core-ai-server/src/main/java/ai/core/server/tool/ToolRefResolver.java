package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
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

    private final Map<String, ToolRegistry> toolRegistry;
    private final InternalApiToolLoader apiToolLoader;
    private final Map<String, List<ToolCall>> dynamicToolSets;
    private final Map<String, List<ToolCall>> apiToolCache = new ConcurrentHashMap<>();

    public ToolRefResolver(Map<String, ToolRegistry> toolRegistry, InternalApiToolLoader apiToolLoader,
                           Map<String, List<ToolCall>> dynamicToolSets) {
        this.toolRegistry = toolRegistry;
        this.apiToolLoader = apiToolLoader;
        this.dynamicToolSets = dynamicToolSets;
    }

    public List<ToolCall> resolve(List<ToolRef> toolRefs) {
        return resolve(toolRefs, null);
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
        if (toolRefs == null || toolRefs.isEmpty()) return List.of();

        var result = new ArrayList<ToolCall>();
        for (var toolRef : toolRefs) {
            if (toolRef == null || toolRef.id == null) continue;

            var type = effectiveType(toolRef);
            if (type != null) {
                switch (type) {
                    case BUILTIN -> resolveBuiltinRef(toolRef, result);
                    case MCP -> resolveMcpRef(toolRef, result, sessionMcpManager);
                    case API -> resolveApiRef(toolRef, result);
                    case AGENT -> LOGGER.debug("skipping AGENT tool ref at registry level, id={}", toolRef.id);
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
        return entryType != null ? entryType : toolRef.type;
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
            // BUILTIN_TOOL_SETS is an immutable Map.of() which throws NPE on get(null)
            var builtinSet = setName != null ? ToolRegistryService.BUILTIN_TOOL_SETS.get(setName) : null;
            if (builtinSet != null) result.addAll(builtinSet);
            return;
        }
        // fallback for dynamically registered builtin tool sets
        var dynamicSet = dynamicToolSets.get(toolRef.id);
        if (dynamicSet != null) result.addAll(dynamicSet);
    }

    private ToolRegistry lookupBuiltinEntry(String id) {
        var entry = toolRegistry.get(id);
        if (entry != null && entry.type == ToolType.BUILTIN) return entry;
        // builtin tools are registered with "builtin:" prefix, e.g. "builtin:builtin-all"
        entry = toolRegistry.get("builtin:" + id);
        if (entry != null && entry.type == ToolType.BUILTIN) return entry;
        return null;
    }

    @SuppressWarnings("checkstyle:NestedIfDepth")
    private void resolveMcpRef(ToolRef toolRef, List<ToolCall> result, McpClientManager sessionMgr) {
        // Handle individual MCP tool: id=mcp-tool:{toolName} (source=serverId) or id=mcp-tool:{serverId}:{toolName}
        var parsed = ToolRef.parseMcpToolId(toolRef.id, toolRef.source);
        if (parsed != null) {
            var serverId = parsed.serverId();
            var toolName = parsed.toolName();
            if (serverId != null) {
                var mgr = pickManager(serverId, sessionMgr);
                if (mgr != null && mgr.hasServer(serverId)) {
                    result.addAll(loadMcpToolSafe(mgr, serverId, toolName));
                    return;
                }
                // Fallback: try the registry entry's resolved name (for config:<name> entries the
                // McpClientManager uses the trailing name, not the config: id).
                var entry = toolRegistry.get(serverId);
                if (entry != null) {
                    var resolvedServerName = resolveMcpServerName(entry);
                    if (resolvedServerName != null) {
                        var fallbackMgr = pickManager(resolvedServerName, sessionMgr);
                        if (fallbackMgr != null && fallbackMgr.hasServer(resolvedServerName)) {
                            result.addAll(loadMcpToolSafe(fallbackMgr, resolvedServerName, toolName));
                            return;
                        }
                    }
                }
            }
            LOGGER.warn("unable to resolve individual mcp tool, id={}, source={}", toolRef.id, toolRef.source);
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

    // Prefer the session manager when it has the requested server (i.e. the session
    // has adopted a sandbox-hosted MCP). Fall back to the global manager for normal
    // STDIO/HTTP servers and for any sandbox-hosted server not in this session.
    private McpClientManager pickManager(String serverName, McpClientManager sessionMgr) {
        if (sessionMgr != null && serverName != null && sessionMgr.hasServer(serverName)) return sessionMgr;
        return McpClientManagerRegistry.getManager();
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
                var builtinSet = ToolRegistryService.BUILTIN_TOOL_SETS.get(setName);
                if (builtinSet != null) result.addAll(builtinSet);
            }
            case API -> result.addAll(resolveApiTools(entry));
            default -> LOGGER.warn("unknown tool type in legacy ref, id={}, type={}", toolRef.id, entry.type);
        }
    }

    private List<ToolCall> resolveMcpTools(ToolRegistry entry, McpClientManager sessionMgr) {
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

    /** Resolve the server name used by McpClientManager from a ToolRegistry entry. */
    private String resolveMcpServerName(ToolRegistry entry) {
        if (entry.id.startsWith(CONFIG_PREFIX)) {
            return entry.id.substring(CONFIG_PREFIX.length());
        }
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager != null && mcpManager.hasServer(entry.id)) {
            return entry.id;
        }
        return null;
    }

    private List<ToolCall> resolveApiTools(ToolRegistry entry) {
        if (API_TOOL_ID.equals(entry.id)) {
            var cached = apiToolCache.get(entry.id);
            if (cached != null) return cached;
        }

        if (entry.id.startsWith("api-app:") && apiToolLoader != null) {
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
