package ai.core.tool.registry;

import ai.core.llm.domain.Tool;
import ai.core.tool.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot produced by {@link ToolRegistry#materialize(ContextSnapshot)}.
 * <p>
 * Binds together the model-visible {@code definitions} and the runtime
 * dispatch map. The internal epoch is checked on every dispatch through
 * {@link ToolRegistry#dispatch(ToolMaterialization, ai.core.llm.domain.FunctionCall, ai.core.agent.ExecutionContext)}
 * — if the registry has changed since materialization, the call is rejected as stale.
 *
 * @author Lim Chen
 */
public class ToolMaterialization {
    private final long epoch;
    private final List<Tool> definitions;
    private final Map<String, ToolCall> dispatchMap;

    ToolMaterialization(long epoch, List<Tool> definitions, Map<String, ToolCall> dispatchMap) {
        this.epoch = epoch;
        this.definitions = List.copyOf(definitions);
        this.dispatchMap = Map.copyOf(dispatchMap);
    }

    public List<Tool> definitions() {
        return definitions;
    }

    Map<String, ToolCall> getDispatchMap() {
        return dispatchMap;
    }

    long epoch() {
        return epoch;
    }
}
