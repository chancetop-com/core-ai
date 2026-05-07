package ai.core.cli.remote;

import ai.core.a2a.InMemoryRemoteAgentContextStore;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.A2ARemoteAgentToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds local tool wrappers for configured remote A2A agents.
 *
 * @author xander
 */
public final class A2ARemoteAgentTools {
    public static List<ToolCall> from(List<A2ARemoteAgentConfig> configs) {
        return from(configs, Set.of());
    }

    public static List<ToolCall> from(List<A2ARemoteAgentConfig> configs, Set<String> reservedToolNames) {
        if (configs == null || configs.isEmpty()) return List.of();
        var tools = new ArrayList<ToolCall>();
        var contextStore = new InMemoryRemoteAgentContextStore();
        var names = normalizedNames(reservedToolNames);
        for (var config : configs) {
            if (!config.enabled) continue;
            var normalizedName = normalizeName(config.name);
            if (!names.add(normalizedName)) {
                throw new IllegalStateException("duplicate A2A remote agent tool name: " + config.name);
            }
            tools.add(tool(config, contextStore));
        }
        return tools;
    }

    private static Set<String> normalizedNames(Set<String> toolNames) {
        var names = new HashSet<String>();
        if (toolNames == null) return names;
        for (var toolName : toolNames) {
            names.add(normalizeName(toolName));
        }
        return names;
    }

    private static String normalizeName(String toolName) {
        return toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
    }

    private static ToolCall tool(A2ARemoteAgentConfig config, InMemoryRemoteAgentContextStore contextStore) {
        var connection = new AtomicReference<A2ARemoteConnector.Connection>();
        return A2ARemoteAgentToolCall.builder()
                .descriptor(config.toDescriptor())
                .clientFactory(() -> connect(config, connection).client())
                .contextStore(contextStore)
                .build();
    }

    private static A2ARemoteConnector.Connection connect(A2ARemoteAgentConfig config,
                                                          AtomicReference<A2ARemoteConnector.Connection> connection) {
        var current = connection.get();
        if (current != null) return current;
        var remoteConfig = new RemoteConfig(config.url, config.resolvedApiKey(), config.agentId, config.name);
        var connected = new A2ARemoteConnector().connect(remoteConfig);
        if (connection.compareAndSet(null, connected)) return connected;
        return connection.get();
    }

    private A2ARemoteAgentTools() {
    }
}
