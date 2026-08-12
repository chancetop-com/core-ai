package ai.core.server.tool;

import ai.core.mcp.client.McpServerConfig;
import ai.core.utils.JsonUtil;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and normalizes standard mcpServers JSON before registry writes begin.
 *
 * @author stephen
 */
final class McpServerImportParser {
    static List<ImportCandidate> parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson.trim())) {
            throw new BadRequestException("MCP import config must be valid JSON");
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtil.fromJson(Map.class, rawJson);
        } catch (RuntimeException e) {
            throw new BadRequestException("MCP import config must be valid JSON", "BAD_REQUEST", e);
        }
        if (!(parsed.get("mcpServers") instanceof Map<?, ?> mcpServers) || mcpServers.isEmpty()) {
            throw new BadRequestException("MCP import config must contain a non-empty 'mcpServers' object");
        }

        var candidates = new ArrayList<ImportCandidate>();
        for (var entry : mcpServers.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (name.isBlank()) {
                throw new BadRequestException("MCP server name must not be blank");
            }
            if (!(entry.getValue() instanceof Map<?, ?> serverConfig)) {
                throw invalid(name, "configuration must be an object");
            }
            var config = normalize(name, serverConfig);
            validateSupportedConfig(name, config);
            candidates.add(new ImportCandidate(name, config, JsonUtil.toJson(serverConfig)));
        }
        return candidates;
    }

    private static Map<String, String> normalize(String name, Map<?, ?> serverConfig) {
        var result = new HashMap<String, String>();
        var command = serverConfig.get("command");
        var url = serverConfig.get("url");
        boolean hasCommand = command instanceof String value && !value.isBlank();
        boolean hasUrl = url instanceof String value && !value.isBlank();
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
            if (result.containsKey(key) || entry.getValue() == null) continue;
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

    private static BadRequestException invalid(String name, String reason) {
        return new BadRequestException("Invalid MCP server '" + name + "': " + reason);
    }

    private McpServerImportParser() {
    }

    record ImportCandidate(String name, Map<String, String> config, String rawConfig) {
    }
}
