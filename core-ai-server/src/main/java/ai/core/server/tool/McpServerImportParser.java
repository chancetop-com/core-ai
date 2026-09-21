package ai.core.server.tool;

import ai.core.mcp.client.McpServerConfig;
import ai.core.utils.JsonUtil;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and normalizes imported MCP configuration before registry writes begin.
 * Accepts either the standard mcpServers wrapper or a single server config. A payload that holds
 * exactly one server is named by the request, or by a top-level 'name' field when there is none.
 *
 * @author stephen
 */
final class McpServerImportParser {
    private static final String MCP_SERVERS = "mcpServers";
    private static final String NAME = "name";
    private static final String UNSUPPORTED_CONFIG =
        "MCP import config must contain a 'mcpServers' object or a single server config with 'command' or 'url'";

    static List<ImportCandidate> parse(String rawJson, String name) {
        Map<?, ?> parsed = parseObject(rawJson);
        if (trimmed(parsed.get("command")) != null || trimmed(parsed.get("url")) != null) {
            return List.of(candidate(resolveName(parsed, name), parsed));
        }
        return parseServers(parsed.get(MCP_SERVERS), name);
    }

    private static Map<?, ?> parseObject(String rawJson) {
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson.trim())) {
            throw new BadRequestException("MCP import config must be valid JSON");
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtil.fromJson(Map.class, rawJson);
        } catch (RuntimeException e) {
            throw new BadRequestException("MCP import config must be valid JSON", "BAD_REQUEST", e);
        }
        if (parsed == null) {
            throw new BadRequestException("MCP import config must be valid JSON");
        }
        return parsed;
    }

    private static List<ImportCandidate> parseServers(Object value, String requestedName) {
        if (value == null) {
            throw new BadRequestException(UNSUPPORTED_CONFIG);
        }
        if (!(value instanceof Map<?, ?> servers) || servers.isEmpty()) {
            throw new BadRequestException("MCP import config must contain a non-empty 'mcpServers' object");
        }

        boolean renameable = servers.size() == 1;
        var candidates = new ArrayList<ImportCandidate>();
        for (var entry : servers.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (name.isBlank()) {
                throw new BadRequestException("MCP server name must not be blank");
            }
            candidates.add(candidate(renameable ? preferredName(name, requestedName) : name, entry.getValue()));
        }
        return candidates;
    }

    private static String preferredName(String declaredName, String requestedName) {
        String name = trimmed(requestedName);
        return name == null ? declaredName : name;
    }

    private static ImportCandidate candidate(String name, Object rawConfig) {
        if (!(rawConfig instanceof Map<?, ?> serverConfig)) {
            throw invalid(name, "configuration must be an object");
        }
        var config = normalize(name, serverConfig);
        validateSupportedConfig(name, config);
        return new ImportCandidate(name, config, JsonUtil.toJson(serverConfig));
    }

    private static String resolveName(Map<?, ?> serverConfig, String requestedName) {
        String name = preferredName(trimmed(serverConfig.get(NAME)), requestedName);
        if (name == null) {
            throw new BadRequestException("MCP import config must include a server name:"
                + " add a top-level 'name' field or specify the name to use");
        }
        return name;
    }

    private static Map<String, String> normalize(String name, Map<?, ?> serverConfig) {
        var result = new HashMap<String, String>();
        var command = serverConfig.get("command");
        var url = serverConfig.get("url");
        boolean hasCommand = trimmed(command) != null;
        boolean hasUrl = trimmed(url) != null;
        if (hasCommand == hasUrl) {
            throw invalid(name, hasCommand
                ? "must define only one of 'command' or 'url'"
                : "must define either 'command' or 'url'");
        }
        if (hasUrl && serverConfig.get("transport") instanceof String transport
            && "sandbox_hosted".equalsIgnoreCase(transport)) {
            throw invalid(name, "cannot use 'sandbox_hosted' transport with a URL");
        }
        if (hasCommand) {
            result.put("transport", "sandbox_hosted");
            result.put("command", (String) command);
        }

        for (var entry : serverConfig.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (NAME.equals(key) || result.containsKey(key) || entry.getValue() == null) continue;
            var value = entry.getValue();
            if (value instanceof String stringValue) {
                result.put(key, stringValue);
            } else if (value instanceof Number || value instanceof Boolean) {
                result.put(key, value.toString());
            } else {
                result.put(key, JsonUtil.toJson(value));
            }
        }
        return result;
    }

    private static void validateSupportedConfig(String name, Map<String, String> config) {
        try {
            McpServerConfig.fromMap(name, new HashMap<>(config));
        } catch (RuntimeException e) {
            throw new BadRequestException("Invalid MCP server '" + name + "': configuration is not supported",
                "BAD_REQUEST", e);
        }
    }

    private static String trimmed(Object value) {
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private static BadRequestException invalid(String name, String reason) {
        return new BadRequestException("Invalid MCP server '" + name + "': " + reason);
    }

    private McpServerImportParser() {
    }

    record ImportCandidate(String name, Map<String, String> config, String rawConfig) {
    }
}
