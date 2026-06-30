package ai.core.server.tool;

import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides API tools resolved via {@link InternalApiToolLoader}.
 * <p>
 * Preserves the three-level granularity of {@code InternalApiToolLoader}:
 * {@code api-app:}, {@code api-service:}, {@code api-operation:}.
 * When the primary tool id yields no results, falls back to {@code loadApiAppTools(source)}.
 *
 * @author Lim Chen
 */
public class ApiToolSetProvider implements ToolProvider {
    private final String id;
    private final InternalApiToolLoader loader;
    private final String toolId;
    private final String source;

    /**
     * @param loader  the API tool loader (may be {@code null} if not initialized)
     * @param toolId  the full tool id (e.g. {@code "api-app:myapp"}, {@code "api-operation:myapp:svc:op"})
     * @param source  fallback app name when {@code toolId} resolution returns empty
     */
    public ApiToolSetProvider(InternalApiToolLoader loader, String toolId, String source) {
        this.id = "api-tools:" + toolId;
        this.loader = loader;
        this.toolId = toolId;
        this.source = source;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int priority() {
        return 8;
    }

    @Override
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.ONCE;
    }

    @Override
    public Map<String, ToolCall> provide() {
        List<ToolCall> tools = List.of();
        if (loader != null && InternalApiToolLoader.isApiToolId(toolId)) {
            tools = loader.loadByToolId(toolId);
        }
        if (tools.isEmpty() && loader != null && source != null) {
            tools = loader.loadApiAppTools(source);
        }
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tc : tools) {
            map.put(tc.getName(), tc);
        }
        return map;
    }
}
