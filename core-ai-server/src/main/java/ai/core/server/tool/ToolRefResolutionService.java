package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.media.MediaProvider;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.llmcall.LLMCallTool;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolProvider;
import ai.core.tool.registry.BuiltinToolProvider;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolProvider.RefreshPolicy;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
import ai.core.tool.github.GitHubTokenProvider;
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


    // ── Fields ───────────────────────────────────────────────────────────────────

    private final Map<String, ToolRegistryEntry> tools;
    private final Map<String, List<ToolCall>> dynamicToolSets;
    private final McpResolutionDependencies mcpDependencies;
    private InternalApiToolLoader internalApiToolLoader;
    private final MediaProvider mediaProvider;
    private final GitHubTokenProvider gitHubTokenProvider;
    private final ai.core.tool.tools.UnderstandVideoTool.VideoUnderstandingService videoService;
    private java.util.function.Function<ai.core.server.gateway.GatewayEndpointType, List<ai.core.tool.tools.MediaModelHint>> mediaModelHintsProvider;
    private AgentDefinitionService agentDefinitionService;
    private LLMCallExecutor llmCallExecutor;
    private ai.core.schedule.ScheduledTaskStore scheduledTaskStore;

    // ── Constructor ──────────────────────────────────────────────────────────────

    ToolRefResolutionService(Map<String, ToolRegistryEntry> tools,
                              Map<String, List<ToolCall>> dynamicToolSets,
                              McpResolutionDependencies mcpDependencies, MediaProvider mediaProvider,
                               GitHubTokenProvider gitHubTokenProvider,
                               ai.core.tool.tools.UnderstandVideoTool.VideoUnderstandingService videoService) {

        this.tools = tools;
        this.dynamicToolSets = dynamicToolSets;
        this.mcpDependencies = mcpDependencies;
        this.mediaProvider = mediaProvider;
        this.gitHubTokenProvider = gitHubTokenProvider;
        this.videoService = videoService;
    }

    // ── Dependency injection (called after construction) ─────────────────────────

    void setInternalApiToolLoader(InternalApiToolLoader internalApiToolLoader) {
        this.internalApiToolLoader = internalApiToolLoader;
    }

    void setAgentDefinitionService(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    void setLlmCallExecutor(LLMCallExecutor llmCallExecutor) {
        this.llmCallExecutor = llmCallExecutor;
    }

    void setMediaModelHintsProvider(java.util.function.Function<ai.core.server.gateway.GatewayEndpointType, List<ai.core.tool.tools.MediaModelHint>> mediaModelHintsProvider) {
        this.mediaModelHintsProvider = mediaModelHintsProvider;
    }

    void setScheduledTaskStore(ai.core.schedule.ScheduledTaskStore scheduledTaskStore) {
        this.scheduledTaskStore = scheduledTaskStore;
    }

    // ── Public API ───────────────────────────────────────────────────────────────

    List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs) {
        return resolveToolRefs(toolRefs, null, null);
    }

    /**
     * Resolve tool refs in the context of a specific session.
     * <p>
     * For SANDBOX_HOSTED MCP refs, this materializes the session sandbox (if not
     * already ready), starts the MCP child process inside it, and registers the
     * client in the session-scoped McpClientManager — so concurrent sessions don't
     * collide on shared server ids. Non-sandbox MCP refs use the global manager.
     * <p>
     * The session's sandbox must already exist (via {@code mcpDependencies.sandboxService().createSandbox})
     * before calling this. If it doesn't, sandbox-hosted refs fall back to the global
     * manager, which won't have them registered (intentionally) — those tools will be
     * unavailable until either this is re-called after the sandbox exists, or the
     * caller invokes {@code AgentSessionManager.loadToolRefs} at runtime.
     *
     * @param sessionId the session/run id; pass {@code null} for non-session callers
     *                  (LLMCallBuilderTools, AgentBuilderTools, etc.).
     */
    List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs, String sessionId) {
        return resolveToolRefs(toolRefs, sessionId, null);
    }

    List<ToolCall> resolveToolRefs(List<ToolRef> toolRefs, String sessionId, String callerUserId) {
        if (toolRefs == null) return List.of();
        validateDeclaredTypes(toolRefs);
        McpClientManager sessionMgr = null;
        if (sessionId != null && mcpDependencies.sandboxService() != null && !toolRefs.isEmpty()) {
            var sandbox = mcpDependencies.sandboxService().getSandbox(sessionId);
            if (sandbox != null) {
                sessionMgr = prepareSessionMcpServers(toolRefs, sessionId, sandbox);
            }
        }
        var resolver = new ToolRefResolver(tools, internalApiToolLoader, dynamicToolSets, mediaProvider, gitHubTokenProvider,
                mcpDependencies.applicationMcpManager());
        resolver.setVideoService(videoService);
        resolver.setAgentDefinitionService(agentDefinitionService);
        resolver.setLlmCallExecutor(llmCallExecutor);
        resolver.setBuiltinEnhancer(this::enhanceMediaToolDescription);
        return resolver.resolve(toolRefs, sessionMgr, callerUserId);
    }

    // replaces media tools with gateway-aware descriptions; runs per resolution so gateway changes take effect quickly
    private List<ToolCall> enhanceMediaToolDescription(List<ToolCall> tools) {
        var imageHints = mediaModelHints(ai.core.server.gateway.GatewayEndpointType.IMAGE_GENERATION);
        // image.edits models must be listed too, otherwise the agent cannot name an image-to-image model
        var imageEditHints = mediaModelHints(ai.core.server.gateway.GatewayEndpointType.IMAGE_EDIT);
        var videoHints = mediaModelHints(ai.core.server.gateway.GatewayEndpointType.VIDEO_GENERATION);
        return tools.stream().map(tool -> enhanceMediaTool(tool, imageHints, imageEditHints, videoHints)).toList();
    }

    private List<ai.core.tool.tools.MediaModelHint> mediaModelHints(ai.core.server.gateway.GatewayEndpointType endpoint) {
        return mediaModelHintsProvider == null ? List.of() : mediaModelHintsProvider.apply(endpoint);
    }

    private ToolCall enhanceMediaTool(ToolCall tool, List<ai.core.tool.tools.MediaModelHint> imageHints, List<ai.core.tool.tools.MediaModelHint> imageEditHints, List<ai.core.tool.tools.MediaModelHint> videoHints) {
        if (tool instanceof ai.core.tool.tools.GenerateImageTool) return ai.core.tool.tools.GenerateImageTool.builder().description(ai.core.tool.tools.GenerateImageTool.buildDescription(imageHints, imageEditHints)).build();
        if (tool instanceof ai.core.tool.tools.GenerateVideoTool) return ai.core.tool.tools.GenerateVideoTool.builder(mediaProvider).description(ai.core.tool.tools.GenerateVideoTool.buildDescription(videoHints)).build();
        if (tool instanceof ai.core.tool.tools.ScheduledTaskTool && scheduledTaskStore != null) return ai.core.tool.tools.ScheduledTaskTool.builder(scheduledTaskStore).build();
        return tool;
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
        return resolveToToolRegistry(toolRefs, sessionId, null);
    }

    ToolRegistry resolveToToolRegistry(List<ToolRef> toolRefs, String sessionId, String callerUserId) {
        var registry = ToolRegistryFactory.createEmpty();
        if (toolRefs == null || toolRefs.isEmpty()) return registry;
        validateDeclaredTypes(toolRefs);

        McpClientManager sessionMgr = null;
        if (sessionId != null && mcpDependencies.sandboxService() != null) {
            var sandbox = mcpDependencies.sandboxService().getSandbox(sessionId);
            if (sandbox != null) {
                sessionMgr = prepareSessionMcpServers(toolRefs, sessionId, sandbox);
            }
        }

        for (var ref : toolRefs) {
            if (ref == null) continue;
            if (AgentDependencyAccessPolicy.isLlmCallRef(ref)) {
                registerLLMCallProvider(registry, ref, callerUserId);
                continue;
            }
            if (ref.id == null) continue;
            var type = effectiveType(ref);
            if (type != null) {
                switch (type) {
                    case BUILTIN -> registerBuiltinProvider(registry, ref);
                    case MCP -> registerMcpProvider(registry, ref, sessionMgr);
                    case API -> registerApiProvider(registry, ref);
                    case AGENT -> LOGGER.debug("skipping AGENT tool ref at registry level, id={}", ref.id);
                    case LLM_CALL -> registerLLMCallProvider(registry, ref, callerUserId);
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

    private String resolveMcpServerName(String name) {
        if (name.startsWith(CONFIG_PREFIX)) return name.substring(CONFIG_PREFIX.length());
        return name;
    }

    private McpClientManager pickMcpManager(String serverName, McpClientManager sessionMgr) {
        if (sessionMgr != null && serverName != null && sessionMgr.hasServer(serverName)) return sessionMgr;
        return mcpDependencies.applicationMcpManager().get();
    }

    private ToolSourceType effectiveType(ToolRef ref) {
        var entry = lookupToolEntry(ref.id);
        return ToolRefResolver.requireCompatibleType(ref, entry != null ? entryType(entry) : null);
    }

    private void validateDeclaredTypes(List<ToolRef> toolRefs) {
        for (var ref : toolRefs) {
            if (ref == null || ref.id == null || AgentDependencyAccessPolicy.isLlmCallRef(ref)) continue;
            var entry = lookupToolEntry(ref.id);
            ToolRefResolver.requireCompatibleType(ref, entry != null ? entryType(entry) : null);
        }
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
                registry.registerProvider(BuiltinToolProvider.fromSet(setName, mediaProvider, gitHubTokenProvider, videoService,
                        this::enhanceMediaToolDescription));
                return;
            }
        }
        var dynamicTools = dynamicToolSets.get(ref.id);
        if (dynamicTools != null) {
            registry.registerProvider(new ListToolProvider("dynamic:" + ref.id, dynamicTools));
            return;
        }
        var matched = ToolRefResolver.resolveDynamicGroupTool(ref.id, dynamicToolSets);
        if (!matched.isEmpty()) registry.registerProvider(new ListToolProvider("dynamic:" + ref.id, matched));
    }

    private void registerMcpProvider(ToolRegistry registry, ToolRef ref,
                                     McpClientManager sessionMgr) {
        var parsed = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (parsed != null) {
            registerMcpFromParsed(registry, parsed, sessionMgr);
            return;
        }

        var entry = tools.get(ref.id);
        var name = entry != null ? resolveMcpServerName(entry.id) : null;
        if (name == null) {
            name = ref.source != null ? ref.source : ref.id;
        }
        registerMcpByName(registry, name, null, sessionMgr);
    }

    private void registerMcpFromParsed(ToolRegistry registry, ToolRef.McpToolId parsed,
                                       McpClientManager sessionMgr) {
        var refServerName = parsed.serverId();
        if (refServerName != null) {
            var name = resolveMcpServerName(refServerName);
            var includes = parsed.toolName() != null ? List.of(parsed.toolName()) : null;
            registerMcpByName(registry, name, includes, sessionMgr);
        }
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
        if (internalApiToolLoader != null && ref.source != null && tools.isEmpty()) {
            tools = internalApiToolLoader.loadApiAppTools(ref.source);
        }
        if (!tools.isEmpty()) {
            registry.registerProvider(new ListToolProvider(ToolProvider.API_TOOLS + ":" + ref.id, tools));
        }
    }

    /**
     * Registers an LLM_CALL tool provider by loading its definition and wrapping it as a callable tool.
     * A missing or invalid definition fails fast — an agent that references a deleted llm-call tool
     * cannot degrade gracefully anyway.
     */
    private void registerLLMCallProvider(ToolRegistry registry, ToolRef ref, String callerUserId) {
        var definitionId = AgentDependencyAccessPolicy.requireLlmCallDefinitionId(ref);
        AgentDependencyAccessPolicy.requireLlmCallCaller(callerUserId);
        if (agentDefinitionService == null) {
            throw new IllegalStateException("LLM_CALL tool resolution requires AgentDefinitionService");
        }
        var definition = agentDefinitionService.resolveLlmCallToolDefinition(definitionId, callerUserId);
        if (llmCallExecutor == null) {
            throw new IllegalStateException("LLM_CALL tool resolution requires LLMCallExecutor");
        }
        // the builtin project writers apply their structured output to the project instead of
        // returning raw JSON (fallback = plain LLM_CALL tool when the project module is absent)
        var tool = ai.core.server.project.ProjectWriterToolSupport.isProjectWriter(definitionId)
            ? ai.core.server.project.ProjectWriterToolSupport.wrap(definitionId, definition, llmCallExecutor)
            : LLMCallTool.create(definition, llmCallExecutor);
        registry.registerProvider(new ListToolProvider("llm-call:" + definitionId, List.of(tool)));
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

        if (mcpDependencies.sandboxService() != null) mcpDependencies.sandboxService().ensureSandboxReady(sessionId);

        var sessionMgr = mcpDependencies.sandboxService() != null ? mcpDependencies.sandboxService().getOrCreateSessionMcpManager(sessionId) : null;
        if (sessionMgr == null) return null;

        var startupTimeout = SandboxConstants.SESSION_MCP_STARTUP_TIMEOUT_SECONDS;

        sandboxHostedEntries.parallelStream().forEach(entry -> {
            try {
                var registered = mcpDependencies.connectionManager().registerOnSession(entry, sessionMgr, sandbox, startupTimeout);
                if (registered && mcpDependencies.sandboxService() != null) {
                    mcpDependencies.sandboxService().recordSessionMcpServer(sessionId, entry.id);
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
