package ai.core.tool.registry;

import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides the built-in toolsets defined in {@link BuiltinTools}.
 * Registered with high priority (10) so that dynamically registered tools
 * can override individual entries by name.
 *
 * @author Lim Chen
 */
public class BuiltinToolProvider implements ToolProvider {
    private final String id;
    private final Map<String, ToolCall> tools;

    public BuiltinToolProvider(String id, List<ToolCall> toolList) {
        this.id = id;
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : toolList) {
            map.put(tc.getName(), tc);
        }
        this.tools = Map.copyOf(map);
    }

    public static BuiltinToolProvider fromSet(String setName) {
        var tools = BuiltinTools.GROUPED_SETS.getOrDefault(setName, List.of());
        return new BuiltinToolProvider(setName, tools);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Map<String, ToolCall> provide() {
        return tools;
    }

    @Override
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.ONCE;
    }
}
