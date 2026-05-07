package ai.core.cli.remote;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Discovers core-ai-server agents and maps them to local A2A remote-agent tool configs.
 *
 * @author xander
 */
public class A2ARemoteAgentDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2ARemoteAgentDiscovery.class);
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(5);
    private static final long CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final ConcurrentMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    static void clearCacheForTesting() {
        CACHE.clear();
    }

    public List<A2ARemoteAgentConfig> discover(A2ARemoteServerConfig server) {
        if (server == null || !server.enabled || !server.discoveryEnabled) return List.of();
        var apiKey = server.resolvedApiKey();
        var key = cacheKey(server, apiKey);
        var cached = CACHE.get(key);
        var now = System.nanoTime();
        if (cached != null && now - cached.createdAtNanos < CACHE_TTL_NANOS) return copyConfigs(cached.configs);

        var json = fetchAgentsJson(server, apiKey);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("empty A2A agent discovery response, server=" + server.id);
        }
        var configs = fromJson(server, json);
        CACHE.put(key, new CacheEntry(now, List.copyOf(copyConfigs(configs))));
        return copyConfigs(configs);
    }

    public RemoteAgentCatalog discoverCatalog(List<A2ARemoteAgentConfig> configs,
                                              List<A2ARemoteServerConfig> serverConfigs) {
        var entries = new ArrayList<RemoteAgentCatalogEntry>();
        var connectionKeys = new HashSet<String>();
        if (configs != null) {
            for (var config : configs) {
                if (config == null || !config.enabled) continue;
                addEntry(entries, connectionKeys, config);
            }
        }
        if (serverConfigs != null) {
            for (var serverConfig : serverConfigs) {
                for (var config : safeDiscover(serverConfig)) {
                    addEntry(entries, connectionKeys, config);
                }
            }
        }
        return new RemoteAgentCatalog(entries);
    }

    protected String fetchAgentsJson(A2ARemoteServerConfig server, String apiKey) {
        return new RemoteApiClient(server.url, apiKey, DISCOVERY_TIMEOUT).getRequired("/api/agents");
    }

    List<A2ARemoteAgentConfig> fromJson(A2ARemoteServerConfig server, String json) {
        var response = JsonUtil.fromJson(ListAgentsResponse.class, json);
        if (response.agents == null || response.agents.isEmpty()) return List.of();

        var configs = new ArrayList<A2ARemoteAgentConfig>();
        var usedNames = new HashSet<String>();
        for (var agent : response.agents) {
            if (!shouldExpose(server, agent)) continue;
            var config = toConfig(server, agent, usedNames);
            configs.add(config);
        }
        return configs;
    }

    private List<A2ARemoteAgentConfig> safeDiscover(A2ARemoteServerConfig serverConfig) {
        if (serverConfig == null || !serverConfig.enabled || !serverConfig.discoveryEnabled) return List.of();
        try {
            return discover(serverConfig);
        } catch (RuntimeException e) {
            var message = "failed to discover A2A remote agents from server '"
                    + serverConfig.id + "': " + e.getMessage();
            if (serverConfig.discoveryRequired) throw new IllegalStateException(message, e);
            LOGGER.warn("{}; remote agents from this server will be unavailable until discovery succeeds", message);
            LOGGER.debug("A2A remote agent discovery failure", e);
            return List.of();
        }
    }

    private void addEntry(List<RemoteAgentCatalogEntry> entries, Set<String> connectionKeys,
                          A2ARemoteAgentConfig config) {
        var key = connectionKey(config);
        if (!connectionKeys.add(key)) return;
        entries.add(toEntry(config));
    }

    private RemoteAgentCatalogEntry toEntry(A2ARemoteAgentConfig config) {
        var id = config.id != null && !config.id.isBlank() ? config.id : config.agentId;
        var serverId = config.serverId != null && !config.serverId.isBlank() ? config.serverId : "manual";
        var name = firstNonBlank(config.displayName, config.name, config.agentId, id);
        return new RemoteAgentCatalogEntry(id, serverId, config.agentId, name, config.description, config.status, config);
    }

    private String connectionKey(A2ARemoteAgentConfig config) {
        return normalizeKey(config.url) + "|" + normalizeKey(config.agentId);
    }

    private String normalizeKey(String value) {
        if (value == null) return "";
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "remote agent";
    }

    private boolean shouldExpose(A2ARemoteServerConfig server, AgentDefinitionView agent) {
        if (agent == null || agent.id == null || agent.id.isBlank()) return false;
        if (agent.type != null && !"AGENT".equalsIgnoreCase(agent.type)) return false;
        if (!server.includeAgents.isEmpty() && !matchesAny(server.includeAgents, agent)) return false;
        return server.excludeAgents.isEmpty() || !matchesAny(server.excludeAgents, agent);
    }

    private boolean matchesAny(List<String> values, AgentDefinitionView agent) {
        for (var value : values) {
            if (matches(value, agent.id) || matches(value, agent.name)) return true;
        }
        return false;
    }

    private boolean matches(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    private A2ARemoteAgentConfig toConfig(A2ARemoteServerConfig server, AgentDefinitionView agent, Set<String> usedNames) {
        var config = new A2ARemoteAgentConfig();
        config.id = server.id + ":" + agent.id;
        config.enabled = true;
        config.url = server.url;
        config.agentId = agent.id;
        config.apiKeyEnv = server.apiKeyEnv;
        config.apiKey = server.apiKey;
        config.serverId = server.id;
        config.name = uniqueToolName(server.toolPrefix, agent, usedNames);
        config.displayName = agent.name != null && !agent.name.isBlank() ? agent.name : agent.id;
        config.description = description(server, agent);
        config.status = agent.status;
        config.discoverable = server.discoverable;
        config.autoDiscovered = true;
        config.timeout = server.timeout;
        config.contextPolicy = server.contextPolicy;
        config.invocationMode = server.invocationMode;
        config.maxInputChars = server.maxInputChars;
        config.maxOutputChars = server.maxOutputChars;
        return config;
    }

    private String uniqueToolName(String toolPrefix, AgentDefinitionView agent, Set<String> usedNames) {
        var prefix = toolPrefix != null && !toolPrefix.isBlank() ? toolPrefix : "server";
        var base = normalizeToolName(prefix + "_" + (agent.name != null && !agent.name.isBlank() ? agent.name : agent.id));
        var candidate = base;
        if (usedNames.add(candidate.toLowerCase(Locale.ROOT))) return candidate;

        var suffix = "_" + shortId(agent.id);
        candidate = withSuffix(base, suffix);
        var count = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = withSuffix(base, suffix + "_" + count);
            count++;
        }
        return candidate;
    }

    private String description(A2ARemoteServerConfig server, AgentDefinitionView agent) {
        var desc = agent.description != null && !agent.description.isBlank()
                ? agent.description
                : "No description provided.";
        var status = agent.status != null && !agent.status.isBlank() ? " Status: " + agent.status + "." : "";
        var name = agent.name != null && !agent.name.isBlank() ? agent.name : agent.id;
        return "Use server-side A2A agent '" + name + "' from remote server '" + server.id
                + "' for tasks that match this agent. " + desc + status;
    }

    private String normalizeToolName(String value) {
        var normalized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = trimUnderscore(normalized);
        if (normalized.isBlank()) normalized = "remote_agent";
        if (normalized.length() > MAX_TOOL_NAME_LENGTH) {
            normalized = normalized.substring(0, MAX_TOOL_NAME_LENGTH);
            normalized = trimUnderscore(normalized);
        }
        return normalized;
    }

    private String withSuffix(String base, String suffix) {
        var maxBaseLength = Math.max(1, MAX_TOOL_NAME_LENGTH - suffix.length());
        var trimmed = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base;
        return trimUnderscore(trimmed) + suffix;
    }

    private String shortId(String id) {
        var normalized = normalizeToolName(id);
        return normalized.length() <= 8 ? normalized : normalized.substring(0, 8);
    }

    private String trimUnderscore(String value) {
        var result = value;
        while (result.startsWith("_")) {
            result = result.substring(1);
        }
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String cacheKey(A2ARemoteServerConfig server, String apiKey) {
        return server.id + "|" + server.url + "|" + Integer.toHexString(apiKey == null ? 0 : apiKey.hashCode())
                + "|" + server.toolPrefix + "|" + String.join(",", server.includeAgents)
                + "|" + String.join(",", server.excludeAgents) + "|" + server.discoverable
                + "|" + server.timeout + "|" + server.contextPolicy + "|" + server.invocationMode
                + "|" + server.maxInputChars + "|" + server.maxOutputChars;
    }

    private List<A2ARemoteAgentConfig> copyConfigs(List<A2ARemoteAgentConfig> configs) {
        if (configs == null || configs.isEmpty()) return List.of();
        var result = new ArrayList<A2ARemoteAgentConfig>();
        for (var config : configs) {
            result.add(copy(config));
        }
        return result;
    }

    private A2ARemoteAgentConfig copy(A2ARemoteAgentConfig config) {
        var copy = new A2ARemoteAgentConfig();
        copy.id = config.id;
        copy.enabled = config.enabled;
        copy.url = config.url;
        copy.agentId = config.agentId;
        copy.apiKeyEnv = config.apiKeyEnv;
        copy.apiKey = config.apiKey;
        copy.serverId = config.serverId;
        copy.name = config.name;
        copy.displayName = config.displayName;
        copy.description = config.description;
        copy.status = config.status;
        copy.discoverable = config.discoverable;
        copy.autoDiscovered = config.autoDiscovered;
        copy.timeout = config.timeout;
        copy.contextPolicy = config.contextPolicy;
        copy.invocationMode = config.invocationMode;
        copy.maxInputChars = config.maxInputChars;
        copy.maxOutputChars = config.maxOutputChars;
        return copy;
    }

    private record CacheEntry(long createdAtNanos, List<A2ARemoteAgentConfig> configs) {
    }
}
