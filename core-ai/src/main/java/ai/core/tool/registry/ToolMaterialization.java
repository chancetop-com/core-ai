package ai.core.tool.registry;

import ai.core.llm.domain.Tool;
import ai.core.tool.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot produced by {@link ToolRegistry#materialize()}.
 *
 * @author Lim Chen
 */
public class ToolMaterialization {
    private final List<Tool> definitions;
    private final Map<String, ToolCall> dispatchMap;
    private final Map<String, String> toolProviderIndex;

    ToolMaterialization(List<Tool> definitions, Map<String, ToolCall> dispatchMap, Map<String, String> toolProviderIndex) {
        this.definitions = List.copyOf(definitions);
        this.dispatchMap = Map.copyOf(dispatchMap);
        this.toolProviderIndex = Map.copyOf(toolProviderIndex);
    }

    public List<Tool> definitions() {
        return definitions;
    }

    public Map<String, ToolCall> getDispatchMap() {
        return dispatchMap;
    }

    /** toolName → providerId (first registration wins due to priority). */
    public Map<String, String> getToolProviderIndex() {
        return toolProviderIndex;
    }
}
