package ai.core.tool.registry;

import ai.core.media.MediaProvider;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.github.GitHubTokenProvider;
import ai.core.tool.tools.UnderstandVideoTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Provides the built-in toolsets defined in {@link BuiltinTools}.
 * Registered with high priority (10) so that dynamically registered tools
 * can override individual entries by name.
 *
 * @author Lim Chen
 */
public class BuiltinToolProvider implements ToolProvider {
    public static BuiltinToolProvider fromSet(String setName) {
        var tools = BuiltinTools.GROUPED_SETS.getOrDefault(setName, List.of());
        return new BuiltinToolProvider(setName, tools);
    }

    public static BuiltinToolProvider fromSet(String setName, MediaProvider mediaProvider,
                                              GitHubTokenProvider gitHubTokenProvider) {
        return new BuiltinToolProvider(setName, BuiltinTools.fromSet(setName, mediaProvider, gitHubTokenProvider));
    }

    public static BuiltinToolProvider fromSet(String setName, MediaProvider mediaProvider,
                                              GitHubTokenProvider gitHubTokenProvider,
                                              UnderstandVideoTool.VideoUnderstandingService videoService) {
        return new BuiltinToolProvider(setName, BuiltinTools.fromSet(setName, mediaProvider, gitHubTokenProvider, videoService));
    }

    /**
     * Builds a builtin tool set once and lets the caller replace individual tools before registration,
     * e.g. to inject a gateway-aware description into generate_video. Use {@link #refreshing} when the
     * enhancer reads state that can change while the provider stays registered.
     */
    public static BuiltinToolProvider fromSet(String setName, MediaProvider mediaProvider,
                                              GitHubTokenProvider gitHubTokenProvider,
                                              UnderstandVideoTool.VideoUnderstandingService videoService,
                                              UnaryOperator<List<ToolCall>> enhancer) {
        var tools = BuiltinTools.fromSet(setName, mediaProvider, gitHubTokenProvider, videoService);
        if (enhancer != null) tools = enhancer.apply(tools);
        return new BuiltinToolProvider(setName, tools);
    }

    /**
     * Builds a builtin tool set that is rebuilt on every materialization, so an enhancer derived from
     * live configuration — the enabled gateway models listed in the generate_image / generate_video
     * descriptions — is re-applied during a long-lived session instead of staying frozen at the
     * moment the session was assembled.
     */
    public static BuiltinToolProvider refreshing(String setName, MediaProvider mediaProvider,
                                                 GitHubTokenProvider gitHubTokenProvider,
                                                 UnderstandVideoTool.VideoUnderstandingService videoService,
                                                 UnaryOperator<List<ToolCall>> enhancer) {
        return new BuiltinToolProvider(setName, () -> {
            var tools = BuiltinTools.fromSet(setName, mediaProvider, gitHubTokenProvider, videoService);
            return enhancer == null ? tools : enhancer.apply(tools);
        }, RefreshPolicy.EVERY_TURN);
    }

    private static Map<String, ToolCall> index(List<ToolCall> toolList) {
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tool : toolList) {
            map.put(tool.getName(), tool);
        }
        return Map.copyOf(map);
    }

    private final String id;
    private final Supplier<Map<String, ToolCall>> tools;
    private final RefreshPolicy refreshPolicy;

    public BuiltinToolProvider(String id, List<ToolCall> toolList) {
        this(id, () -> toolList, RefreshPolicy.ONCE);
    }

    private BuiltinToolProvider(String id, Supplier<List<ToolCall>> toolList, RefreshPolicy refreshPolicy) {
        this.id = id;
        this.tools = () -> index(toolList.get());
        this.refreshPolicy = refreshPolicy;
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
        return tools.get();
    }

    @Override
    public RefreshPolicy refreshPolicy() {
        return refreshPolicy;
    }
}
