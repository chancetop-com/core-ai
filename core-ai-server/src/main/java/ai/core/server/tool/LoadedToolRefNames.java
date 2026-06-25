package ai.core.server.tool;

import ai.core.api.server.session.IdName;
import ai.core.server.domain.ToolRef;

import java.util.List;

/**
 * Converts stored ToolRef ids to stable API id/name pairs for chat UI state.
 */
public class LoadedToolRefNames {
    public static List<IdName> toIdNames(List<ToolRef> refs, ToolRegistryService toolRegistryService) {
        return refs.stream()
                .map(ref -> toIdName(ref, toolRegistryService))
                .toList();
    }

    public static IdName toIdName(ToolRef ref, ToolRegistryService toolRegistryService) {
        var v = new IdName();
        v.id = ref.id;
        v.name = displayName(ref, toolRegistryService);
        return v;
    }

    private static String displayName(ToolRef ref, ToolRegistryService toolRegistryService) {
        if (ref == null || ref.id == null) return "";
        if (toolRegistryService != null) {
            try {
                var tool = toolRegistryService.getTool(ref.id);
                if (tool.name != null && !tool.name.isBlank()) return tool.name;
            } catch (Exception ignored) {
                // Composite refs such as mcp-tool:{server}:{tool} do not exist as registry rows.
            }
        }
        var mcpTool = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (mcpTool != null && mcpTool.toolName() != null && !mcpTool.toolName().isBlank()) {
            return mcpTool.toolName();
        }
        if (ref.id.startsWith("api-operation:")) return suffixAfterLastColon(ref.id);
        if (ref.id.startsWith("api-service:")) return ref.id.substring("api-service:".length());
        if (ref.id.startsWith("api-app:")) return ref.id.substring("api-app:".length());
        if ("builtin-service-api".equals(ref.id)) return "service-api";
        if (ref.id.startsWith("builtin:")) return ref.id.substring("builtin:".length());
        return ref.id;
    }

    private static String suffixAfterLastColon(String id) {
        var idx = id.lastIndexOf(':');
        return idx >= 0 && idx + 1 < id.length() ? id.substring(idx + 1) : id;
    }
}
