package ai.core.cli.remote;

import ai.core.a2a.A2ARemoteAgentDescriptor;
import ai.core.bootstrap.PropertiesFileSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a2a.remoteAgents.* entries from agent.properties.
 *
 * @author xander
 */
public final class A2ARemoteAgentConfigLoader {
    private static final String ROOT = "a2a.remoteAgents";

    public static List<A2ARemoteAgentConfig> load(PropertiesFileSource props) {
        var ids = props.property(ROOT).orElse("");
        if (ids.isBlank()) return List.of();
        var configs = new ArrayList<A2ARemoteAgentConfig>();
        for (var id : ids.split(",")) {
            var trimmed = id.trim();
            if (!trimmed.isBlank()) configs.add(loadOne(props, trimmed));
        }
        return configs;
    }

    private static A2ARemoteAgentConfig loadOne(PropertiesFileSource props, String id) {
        var prefix = ROOT + "." + id;
        var config = new A2ARemoteAgentConfig();
        config.id = id;
        config.enabled = props.property(prefix + ".enabled").map(Boolean::parseBoolean).orElse(true);
        config.url = props.property(prefix + ".url").orElse(null);
        config.agentId = props.property(prefix + ".agentId").orElse(null);
        config.apiKeyEnv = props.property(prefix + ".apiKeyEnv").orElse(null);
        config.apiKey = props.property(prefix + ".apiKey").orElse(null);
        config.name = normalizeToolName(props.property(prefix + ".name").orElse(defaultName(config)));
        config.description = props.property(prefix + ".description").orElse(defaultDescription(id));
        config.timeout = props.property(prefix + ".timeout").map(A2ARemoteAgentConfigLoader::duration).orElse(config.timeout);
        config.contextPolicy = props.property(prefix + ".contextPolicy").map(A2ARemoteAgentConfigLoader::contextPolicy).orElse(config.contextPolicy);
        config.invocationMode = props.property(prefix + ".invocationMode").map(A2ARemoteAgentConfigLoader::invocationMode).orElse(config.invocationMode);
        config.maxInputChars = props.property(prefix + ".maxInputChars").map(Integer::parseInt).orElse(config.maxInputChars);
        config.maxOutputChars = props.property(prefix + ".maxOutputChars").map(Integer::parseInt).orElse(config.maxOutputChars);
        validate(config, prefix);
        return config;
    }

    private static String defaultName(A2ARemoteAgentConfig config) {
        var suffix = config.agentId != null && !config.agentId.isBlank() ? config.agentId : config.id;
        return "remote_" + suffix;
    }

    private static String defaultDescription(String id) {
        return "Use remote A2A agent '" + id + "' for server-side tools, MCP, managed sandbox, and internal systems.";
    }

    private static void validate(A2ARemoteAgentConfig config, String prefix) {
        if (!config.enabled) return;
        if (config.url == null || config.url.isBlank()) {
            throw new IllegalStateException("required property not found: " + prefix + ".url");
        }
    }

    private static String normalizeToolName(String value) {
        var normalized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = trimUnderscore(normalized);
        if (normalized.isBlank()) normalized = "remote_agent";
        if (normalized.length() > 64) normalized = normalized.substring(normalized.length() - 64);
        return normalized;
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

    private static A2ARemoteAgentDescriptor.ContextPolicy contextPolicy(String value) {
        return A2ARemoteAgentDescriptor.ContextPolicy.valueOf(enumValue(value));
    }

    private static A2ARemoteAgentDescriptor.InvocationMode invocationMode(String value) {
        return A2ARemoteAgentDescriptor.InvocationMode.valueOf(enumValue(value));
    }

    private static String enumValue(String value) {
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static Duration duration(String value) {
        var trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.endsWith("ms")) return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2)));
        if (trimmed.endsWith("s")) return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        if (trimmed.endsWith("m")) return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        if (trimmed.endsWith("h")) return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        return Duration.ofSeconds(Long.parseLong(trimmed));
    }

    private A2ARemoteAgentConfigLoader() {
    }
}
