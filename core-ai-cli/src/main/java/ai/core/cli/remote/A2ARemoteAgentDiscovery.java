package ai.core.cli.remote;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Discovers core-ai-server agents and maps them to local A2A remote-agent tool configs.
 *
 * @author xander
 */
public class A2ARemoteAgentDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2ARemoteAgentDiscovery.class);
    private static final int MAX_TOOL_NAME_LENGTH = 64;

    public List<A2ARemoteAgentConfig> discover(A2ARemoteServerConfig server) {
        if (server == null || !server.enabled || !server.discoveryEnabled) return List.of();
        var json = new RemoteApiClient(server.url, server.resolvedApiKey()).get("/api/agents");
        if (json == null || json.isBlank()) {
            LOGGER.warn("failed to discover A2A agents, server={}", server.id);
            return List.of();
        }
        return fromJson(server, json);
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
        config.name = uniqueToolName(server.toolPrefix, agent, usedNames);
        config.description = description(server, agent);
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
}
