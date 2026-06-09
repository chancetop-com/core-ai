package ai.core.tool.registry;

import ai.core.llm.domain.Tool;
import ai.core.tool.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot produced by {@link ToolRegistry#materialize()}.
 * Binds together the model-visible definitions and the runtime dispatch map.
 *
 * @author Lim Chen
 */
public class ToolMaterialization {
    private final List<Tool> definitions;
    private final Map<String, ToolCall> dispatchMap;

    ToolMaterialization(List<Tool> definitions, Map<String, ToolCall> dispatchMap) {
        this.definitions = List.copyOf(definitions);
        this.dispatchMap = Map.copyOf(dispatchMap);
    }

    public List<Tool> definitions() {
        return definitions;
    }

    public Map<String, ToolCall> getDispatchMap() {
        return dispatchMap;
    }
}
