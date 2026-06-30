package ai.core.server.tool;

import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides a builtin tool set identified by its {@code setName} (e.g. {@code "builtin-planning"}).
 * Tools are looked up from {@link BuiltinTools#GROUPED_SETS} and cached indefinitely.
 *
 * @author Lim Chen
 */
public class BuiltinToolSetProvider implements ToolProvider {
    private final String id;
    private final String setName;

    public BuiltinToolSetProvider(String setName) {
        this.id = "server-builtin:" + setName;
        this.setName = setName;
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
        var tools = BuiltinTools.GROUPED_SETS.get(setName);
        if (tools == null) return Map.of();
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : tools) {
            map.put(tc.getName(), tc);
        }
        return map;
    }
}
