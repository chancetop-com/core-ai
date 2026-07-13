package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.sandbox.SandboxService;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolProvider;
import ai.core.tool.registry.BuiltinToolProvider;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolProvider.RefreshPolicy;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves {@link ToolRef}s into concrete {@link ToolCall} lists or core {@link ToolRegistry} instances.
 * <p>
 * Handles MCP sandbox isolation per-session, source type dispatch (BUILTIN/MCP/API/AGENT),
 * and provider registration for tool execution.
 *
 * @author stephen
 */
class ToolRefResolutionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRefResolutionService.class);
    private static final String CONFIG_PREFIX = ToolRegistryService.CONFIG_PREFIX;

    // ── Static helpers ───────────────────────────────────────────────────────────

    private static ToolSourceType entryType(ToolRegistryEntry entry) {
        return switch (entry.type) {
            case BUILTIN -> ToolSourceType.BUILTIN;
            case MCP -> ToolSourceType.MCP;
            case API -> ToolSourceType.API;
        };
    }

    static String resolveMcpServerName(String name) {
        if (name.startsWith(CONFIG_PREFIX)) {
            return name.substring(CONFIG_PREFIX.length());
        }
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager != null && mcpManager.hasServer(name)) return name;
        return name;
    }

    private static McpClientManager pickMcpManager(String serverName, McpClientManager sessionMgr) {
        if (sessionMgr != null && serverName != null && sessionMgr.hasServer(serverName)) return sessionMgr;
        return McpClientManagerRegistry.getManager();
    }

    // ── Fields ───────────────────────────────────────────────────────────────────

    private final Map<String, ToolRegistryEntry> tools;
    private final Map<String, List<ToolCall>> dynamicToolSets;
    private final McpServerConnectionManager mcpConnectionManager;
    private InternalApiToolLoader internalApiToolLoader;
    private SandboxService sandboxService;

    // ── Constructor ──────────────────────────────────────────────────────────────

    ToolRefResolutionService(Map<String, ToolRegistryEntry> tools,
                             Map<String, List<ToolCall>> dynamicToolSets,
                             McpServerConnectionManager mcpConnectionManager) {
        this.tools = tools;
        this.dynamicToolSets = dynamicToolSets;
        this.mcpConnectionManager = mcpConnectionManager;
    }

    // ── Dependency injection (called after construction) ─────────────────────────

    void setInternalApiToolLoader(InternalApiToolLoader internalApiToolLoader) {
        this.internalApiToolLoader = internalApiToolLoader;
    }

    void setSandboxService(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs) {
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
    List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs, String sessionId) {
        if (toolRefs == null) return List.of();
        McpClientManager sessionMgr = null;
        if (sessionId != null && sandboxService != null && !toolRefs.isEmpty()) {
            var sandbox = sandboxService.getSandbox(sessionId);
            if (sandbox != null) {
                sessionMgr = prepareSessionMcpServers(toolRefs, sessionId, sandbox);
            }
        }
        return new ToolRefResolver(tools, internalApiToolLoader, dynamicToolSets).resolve(toolRefs, sessionMgr);
    }

    /**
     * Resolves {@link ToolRef}s into a core {@link ToolRegistry}
     * populated with appropriate {@link ToolProvider}s.
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
    ToolRegistry resolveToToolRegistry(List<ToolRef> toolRefs, String sessionId) {
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

    List<String> extractAgentIds(List<ToolRef> toolRefs) {
        if (toolRefs == null || toolRefs.isEmpty()) return List.of();
        return toolRefs.stream()
                .filter(ref -> ref.type == ToolSourceType.AGENT)
                .map(ref -> ref.id)
                .toList();
    }

    public void reloadApiTools() {
        if (internalApiToolLoader == null) {
            LOGGER.warn("InternalApiToolLoader not initialized, skipping reload");
            return;
        }
        try {
            internalApiToolLoader.load();
            LOGGER.info("reloaded Service API tools");
        } catch (Exception e) {
            LOGGER.error("failed to reload Service API tools", e);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private ToolSourceType effectiveType(ToolRef ref) {
        var entry = lookupToolEntry(ref.id);
        return entry != null ? entryType(entry) : ref.type;
    }

    private ToolRegistryEntry lookupToolEntry(String id) {
        var entry = tools.get(id);
        if (entry == null) entry = tools.get("builtin:" + id);
        return entry;
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

    // ── Session MCP startup ──────────────────────────────────────────────────────

    private McpClientManager prepareSessionMcpServers(List<ToolRef> toolRefs, String sessionId, Sandbox sandbox) {
        var sandboxHostedEntries = collectSandboxHostedEntries(toolRefs);
        if (sandboxHostedEntries.isEmpty()) return null;

        if (sandboxService != null) sandboxService.ensureSandboxReady(sessionId);

        var sessionMgr = sandboxService != null ? sandboxService.getOrCreateSessionMcpManager(sessionId) : null;
        if (sessionMgr == null) return null;

        var startupTimeout = SandboxConstants.SESSION_MCP_STARTUP_TIMEOUT_SECONDS;

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
        var parsed = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (parsed != null && parsed.serverId() != null) {
            var serverEntry = tools.get(parsed.serverId());
            if (serverEntry != null && serverEntry.type == ToolType.MCP) return serverEntry;
        }
        if (ref.source != null) {
            var srcEntry = tools.get(ref.source);
            if (srcEntry != null && srcEntry.type == ToolType.MCP) return srcEntry;
        }
        return null;
    }
}
