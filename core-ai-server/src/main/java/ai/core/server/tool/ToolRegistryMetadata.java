package ai.core.server.tool;

import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.tool.ToolCall;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Metadata-only tool projections used by the publish gate. They read the registry runtime resolves but
 * never materialize an MCP session or execute a tool, so a publish check has no external side effect.
 *
 * @author core-ai
 */
public final class ToolRegistryMetadata {
    public static ToolRegistryEntry findToolEntry(ToolRegistryService registry, String id) {
        if (id == null) return null;
        var entry = registry.toolEntries().get(id);
        if (entry == null) entry = registry.toolEntries().get("builtin:" + id);
        return entry;
    }

    public static List<String> effectiveBuiltinToolNames(ToolRegistryService registry, List<ToolRef> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        var names = new LinkedHashSet<String>();
        for (var ref : refs) {
            if (ref == null || ref.id == null) continue;
            if (ref.type != null && ref.type != ToolSourceType.BUILTIN) continue;
            var entry = findToolEntry(registry, ref.id);
            if (entry != null && entry.type == ToolType.BUILTIN) {
                var set = entry.config == null ? null : entry.config.get("set");
                var groupTools = set != null ? registry.dynamicToolSets().get(entry.id) : null;
                if (groupTools != null) groupTools.forEach(tool -> names.add(tool.getName()));
                continue;
            }
            var parsed = builtinGroupToolId(ref.id);
            if (parsed == null) continue;
            var groupTools = registry.dynamicToolSets().get(parsed.groupId());
            if (groupTools == null) continue;
            groupTools.stream().filter(tool -> parsed.toolName().equals(tool.getName()))
                .forEach(tool -> names.add(tool.getName()));
        }
        return List.copyOf(names);
    }

    public static boolean builtinRefAvailable(ToolRegistryService registry, ToolRef ref) {
        if (ref == null || ref.id == null) return false;
        var entry = findToolEntry(registry, ref.id);
        if (entry != null && entry.type == ToolType.BUILTIN) return true;
        return !effectiveBuiltinToolNames(registry, List.of(ref)).isEmpty();
    }

    public static List<String> apiToolNames(ToolRegistryService registry, String toolId) {
        if (toolId == null || !InternalApiToolLoader.isApiToolId(toolId)) return List.of();
        var loader = registry.apiLoader();
        if (loader == null) return List.of();
        try {
            List<ToolCall> tools = loader.loadByToolId(toolId);
            return tools.stream().map(ToolCall::getName).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static ToolCall findBuiltinTool(ToolRegistryService registry, String toolName,
                                           ai.core.media.MediaProvider mediaProvider,
                                           ai.core.tool.github.GitHubTokenProvider gitHubTokenProvider,
                                           ai.core.tool.tools.UnderstandVideoTool.VideoUnderstandingService videoService) {
        for (List<ToolCall> tools : registry.dynamicToolSets().values()) {
            for (var tool : tools) {
                if (toolName.equals(tool.getName())) return tool;
            }
        }
        for (var tool : ai.core.tool.BuiltinTools.fromSet(ai.core.tool.registry.ToolProvider.BUILTIN_ALL,
            mediaProvider, gitHubTokenProvider, videoService)) {
            if (toolName.equals(tool.getName())) return tool;
        }
        return null;
    }

    private static ToolRef.BuiltinGroupToolId builtinGroupToolId(String id) {
        var parsed = ToolRef.parseBuiltinGroupToolId(id);
        if (parsed == null && !id.startsWith("builtin:")) {
            parsed = ToolRef.parseBuiltinGroupToolId("builtin:" + id);
        }
        return parsed;
    }

    private ToolRegistryMetadata() {
    }
}
