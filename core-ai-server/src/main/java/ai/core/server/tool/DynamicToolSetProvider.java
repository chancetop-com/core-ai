package ai.core.server.tool;

import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides dynamically registered tool sets (e.g. {@code builtin-agent-builder},
 * {@code builtin-llm-call-builder}) as a {@link ToolProvider}.
 * <p>
 * Tools are provided once at construction time and cached indefinitely.
 *
 * @author Lim Chen
 */
public class DynamicToolSetProvider implements ToolProvider {
    private final String id;
    private final List<ToolCall> tools;

    public DynamicToolSetProvider(String toolSetId, List<ToolCall> tools) {
        this.id = ToolProvider.DYNAMIC + ":" + toolSetId;
        this.tools = List.copyOf(tools);
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
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.ONCE;
    }

    @Override
    public Map<String, ToolCall> provide() {
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : tools) {
            map.put(tc.getName(), tc);
        }
        return map;
    }
}
