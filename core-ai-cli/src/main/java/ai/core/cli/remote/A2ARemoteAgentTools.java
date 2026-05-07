package ai.core.cli.remote;

import ai.core.a2a.InMemoryRemoteAgentContextStore;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.A2ARemoteAgentToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(A2ARemoteAgentTools.class);

    public static List<ToolCall> from(List<A2ARemoteAgentConfig> configs) {
        return from(configs, List.of(), Set.of());
    }

    public static List<ToolCall> from(List<A2ARemoteAgentConfig> configs, Set<String> reservedToolNames) {
        return from(configs, List.of(), reservedToolNames);
    }

    public static List<ToolCall> from(List<A2ARemoteAgentConfig> configs, List<A2ARemoteServerConfig> serverConfigs,
                                      Set<String> reservedToolNames) {
        return from(configs, serverConfigs, reservedToolNames, new A2ARemoteAgentDiscovery());
    }

    static List<ToolCall> from(List<A2ARemoteAgentConfig> configs, List<A2ARemoteServerConfig> serverConfigs,
                               Set<String> reservedToolNames, A2ARemoteAgentDiscovery discovery) {
        if ((configs == null || configs.isEmpty()) && (serverConfigs == null || serverConfigs.isEmpty())) return List.of();
        var tools = new ArrayList<ToolCall>();
        var contextStore = new InMemoryRemoteAgentContextStore();
        var names = normalizedNames(reservedToolNames);
        if (configs != null) {
            for (var config : configs) {
                if (!config.enabled) continue;
                addTool(tools, names, contextStore, config);
            }
        }
        if (serverConfigs != null) {
            for (var serverConfig : serverConfigs) {
                var discoveredConfigs = discover(serverConfig, discovery);
                if (discoveredConfigs == null) continue;
                for (var discovered : discoveredConfigs) {
                    addTool(tools, names, contextStore, discovered);
                }
            }
        }
        return tools;
    }

    private static void addTool(List<ToolCall> tools, Set<String> names, InMemoryRemoteAgentContextStore contextStore,
                                A2ARemoteAgentConfig config) {
        var normalizedName = normalizeName(config.name);
        if (!names.add(normalizedName)) {
            if (!config.autoDiscovered) {
                throw new IllegalStateException("duplicate A2A remote agent tool name: " + config.name);
            }
            config.name = renamed(config.name, config.agentId, names);
        }
        tools.add(tool(config, contextStore));
    }

    private static List<A2ARemoteAgentConfig> discover(A2ARemoteServerConfig serverConfig,
                                                       A2ARemoteAgentDiscovery discovery) {
        if (serverConfig == null || !serverConfig.enabled || !serverConfig.discoveryEnabled) return List.of();
        try {
            return discovery.discover(serverConfig);
        } catch (RuntimeException e) {
            var message = "failed to discover A2A remote agents from server '"
                    + serverConfig.id + "': " + e.getMessage();
            if (serverConfig.discoveryRequired) {
                throw new IllegalStateException(message, e);
            }
            LOGGER.warn("{}; remote agents from this server will be unavailable until discovery succeeds", message);
            LOGGER.debug("A2A remote agent discovery failure", e);
            return List.of();
        }
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

    private static String renamed(String name, String agentId, Set<String> names) {
        var suffix = "_" + shortId(agentId);
        var base = trimToMaxLength(name, 64 - suffix.length());
        var candidate = base + suffix;
        var count = 2;
        while (!names.add(normalizeName(candidate))) {
            var numberedSuffix = suffix + "_" + count;
            base = trimToMaxLength(name, 64 - numberedSuffix.length());
            candidate = base + numberedSuffix;
            count++;
        }
        return candidate;
    }

    private static String trimToMaxLength(String name, int maxLength) {
        var value = name != null && !name.isBlank() ? name : "remote_agent";
        if (value.length() <= maxLength) return trimUnderscore(value);
        return trimUnderscore(value.substring(0, Math.max(1, maxLength)));
    }

    private static String shortId(String agentId) {
        if (agentId == null || agentId.isBlank()) return "agent";
        var normalized = agentId.replaceAll("[^A-Za-z0-9_-]", "_");
        normalized = trimUnderscore(normalized);
        if (normalized.isBlank()) return "agent";
        return normalized.length() <= 8 ? normalized : normalized.substring(0, 8);
    }

    private static String trimUnderscore(String value) {
        var result = value;
        while (result.startsWith("_")) {
            result = result.substring(1);
        }
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static ToolCall tool(A2ARemoteAgentConfig config, InMemoryRemoteAgentContextStore contextStore) {
        var connection = new AtomicReference<A2ARemoteConnector.Connection>();
        return A2ARemoteAgentToolCall.builder()
                .descriptor(config.toDescriptor())
                .clientFactory(() -> connect(config, connection).client())
                .contextStore(contextStore)
                .discoverable(config.discoverable)
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
