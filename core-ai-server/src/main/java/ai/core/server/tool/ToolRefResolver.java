package ai.core.server.tool;

import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistry;
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
    private final Map<String, List<ToolCall>> apiToolCache = new ConcurrentHashMap<>();

    public ToolRefResolver(Map<String, ToolRegistry> toolRegistry, InternalApiToolLoader apiToolLoader) {
        this.toolRegistry = toolRegistry;
        this.apiToolLoader = apiToolLoader;
    }

    public List<ToolCall> resolve(List<ToolRef> toolRefs) {
        if (toolRefs == null || toolRefs.isEmpty()) return List.of();

        var result = new ArrayList<ToolCall>();
        for (var toolRef : toolRefs) {
            if (toolRef == null || toolRef.id == null) continue;

            if (toolRef.type != null) {
                switch (toolRef.type) {
                    case BUILTIN -> resolveBuiltinRef(toolRef, result);
                    case MCP -> resolveMcpRef(toolRef, result);
                    case API -> resolveApiRef(toolRef, result);
                    case AGENT -> LOGGER.debug("skipping AGENT tool ref at registry level, id={}", toolRef.id);
                    default -> LOGGER.warn("unknown tool type, id={}, type={}", toolRef.id, toolRef.type);
                }
            } else {
                resolveLegacyRef(toolRef, result);
            }
        }
        return result;
    }

    private void resolveBuiltinRef(ToolRef toolRef, List<ToolCall> result) {
        var entry = toolRegistry.get(toolRef.id);
        if (entry != null && entry.type == ToolType.BUILTIN) {
            var setName = entry.config != null ? entry.config.get("set") : null;
            var builtinSet = ToolRegistryService.BUILTIN_TOOL_SETS.get(setName);
            if (builtinSet != null) result.addAll(builtinSet);
        }
    }

    private void resolveMcpRef(ToolRef toolRef, List<ToolCall> result) {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null) return;

        var serverName = toolRef.source != null ? toolRef.source : toolRef.id;
        var entry = toolRegistry.get(toolRef.id);
        if (entry != null) {
            result.addAll(resolveMcpTools(entry));
            return;
        }

        if (mcpManager.hasServer(serverName)) {
            result.addAll(McpToolCalls.from(mcpManager, List.of(serverName), null));
        }
    }

    private void resolveApiRef(ToolRef toolRef, List<ToolCall> result) {
        var entry = toolRegistry.get(toolRef.id);
        if (entry != null) {
            result.addAll(resolveApiTools(entry));
            return;
        }

        if (apiToolLoader != null && toolRef.source != null) {
            result.addAll(apiToolLoader.loadApiAppTools(toolRef.source));
        }
    }

    private void resolveLegacyRef(ToolRef toolRef, List<ToolCall> result) {
        var entry = toolRegistry.get(toolRef.id);
        if (entry == null) return;

        switch (entry.type) {
            case MCP -> result.addAll(resolveMcpTools(entry));
            case BUILTIN -> {
                var setName = entry.config != null ? entry.config.get("set") : null;
                var builtinSet = ToolRegistryService.BUILTIN_TOOL_SETS.get(setName);
                if (builtinSet != null) result.addAll(builtinSet);
            }
            case API -> result.addAll(resolveApiTools(entry));
            default -> LOGGER.warn("unknown tool type in legacy ref, id={}, type={}", toolRef.id, entry.type);
        }
    }

    private List<ToolCall> resolveMcpTools(ToolRegistry entry) {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager != null && mcpManager.hasServer(entry.id)) {
            return new ArrayList<>(McpToolCalls.from(mcpManager, List.of(entry.id), null));
        } else if (entry.id.startsWith(CONFIG_PREFIX)) {
            var serverName = entry.id.substring(CONFIG_PREFIX.length());
            if (mcpManager != null && mcpManager.hasServer(serverName)) {
                return new ArrayList<>(McpToolCalls.from(mcpManager, List.of(serverName), null));
            }
        }
        return List.of();
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
