package ai.core.mcp.client;

import ai.core.utils.JsonUtil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code ${env:NAME}} placeholders from a per-server secret map. Only the desktop-managed
 * MCP path uses this: the CLI keeps passing env/header values through verbatim.
 *
 * @author stephen
 */
public final class McpSecretExpander {
    public static final Pattern REFERENCE = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    public static final class MissingSecretException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final String serverName;
        private final String secretName;

        MissingSecretException(String serverName, String secretName) {
            super("MCP server '" + serverName + "' references undefined secret " + secretName);
            this.serverName = serverName;
            this.secretName = secretName;
        }

        public String serverName() {
            return serverName;
        }

        public String secretName() {
            return secretName;
        }
    }

    public static Map<String, Object> expand(String serverName, Map<String, Object> config, Map<String, String> secrets) {
        var resolvedSecrets = secrets == null ? Map.<String, String>of() : secrets;
        var copy = new LinkedHashMap<>(config);
        if (config.get("args") instanceof List<?> args) {
            copy.put("args", args.stream().map(arg -> expandValue(serverName, arg, resolvedSecrets)).toList());
        }
        copy.put("env", expandEnv(serverName, config.get("env"), resolvedSecrets));
        copy.put("url", expandValue(serverName, config.get("url"), resolvedSecrets));
        copy.put("endpoint", expandValue(serverName, config.get("endpoint"), resolvedSecrets));
        copy.put("headers", expandHeaders(serverName, config.get("headers"), resolvedSecrets));
        return copy;
    }

    public static String expandValue(String serverName, Object value, Map<String, String> secrets) {
        if (!(value instanceof String text) || text.indexOf("${env:") < 0) {
            return value instanceof String ? (String) value : null;
        }
        var matcher = REFERENCE.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            var name = matcher.group(1);
            var secret = secrets.get(name);
            if (secret == null) {
                throw new MissingSecretException(serverName, name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(secret));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Object expandEnv(String serverName, Object env, Map<String, String> secrets) {
        if (env == null) return null;
        var parsed = parseObject(env);
        if (parsed == null) return env;
        var expanded = new LinkedHashMap<String, String>();
        parsed.forEach((key, value) -> expanded.put(key, expandValue(serverName, value, secrets)));
        return expanded;
    }

    private static Object expandHeaders(String serverName, Object headers, Map<String, String> secrets) {
        if (headers == null) return null;
        var parsed = parseObject(headers);
        if (parsed == null) return headers;
        var expanded = new LinkedHashMap<String, String>();
        parsed.forEach((key, value) -> expanded.put(key, expandValue(serverName, value, secrets)));
        return expanded;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new HashMap<String, Object>();
            map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return JsonUtil.fromJson(Map.class, text);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private McpSecretExpander() {
    }
}
