package ai.core.tool.registry;

import ai.core.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wraps a {@link List}&lt;{@link ToolCall}&gt; as a {@link ToolProvider}.
 * <p>
 * When no explicit {@code id} is given, one is derived from the tool names so that
 * different tool sets map to different ids and won't silently overwrite each other.
 *
 * @author Lim Chen
 */
public class ListToolProvider implements ToolProvider {
    private final String id;
    private final List<ToolCall> tools;

    public ListToolProvider(List<ToolCall> tools) {
        this(tools.stream().map(ToolCall::getName).sorted().collect(Collectors.joining(",")), tools);
    }

    public ListToolProvider(String id, List<ToolCall> tools) {
        this.id = id;
        this.tools = tools;
    }

    public static ListToolProvider of(List<ToolCall> tools) {
        return new ListToolProvider(tools);
    }

    public static ListToolProvider of(String id, List<ToolCall> tools) {
        return new ListToolProvider(id, tools);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int priority() {
        return 5;
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
