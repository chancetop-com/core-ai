package ai.core.mcp.client;

import ai.core.utils.JsonUtil;

import java.io.Serial;
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
 * Secret placeholders are allowed strictly inside {@code env} values and {@code headers} values.
 * Any {@code ${env:} placeholder in {@code command}, {@code args}, {@code url}, or {@code endpoint}
 * is rejected with {@link UnsupportedSecretLocationException} without echoing the raw value.
 *
 * @author stephen
 */
public final class McpSecretExpander {
    public static final Pattern REFERENCE = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");
    public static final String PLACEHOLDER_PREFIX = "${env:";

    public static Map<String, Object> expand(String serverName, Map<String, Object> config, Map<String, String> secrets) {
        rejectUnsupportedLocations(serverName, config);
        var resolvedSecrets = secrets == null ? Map.<String, String>of() : secrets;
        var copy = new LinkedHashMap<>(config);
        copy.put("env", expandEnv(serverName, config.get("env"), resolvedSecrets));
        copy.put("headers", expandHeaders(serverName, config.get("headers"), resolvedSecrets));
        return copy;
    }

    public static void rejectUnsupportedLocations(String serverName, Map<String, Object> config) {
        if (config == null) return;
        rejectIfPlaceholder(serverName, "command", config.get("command"));
        rejectIfPlaceholder(serverName, "url", config.get("url"));
        rejectIfPlaceholder(serverName, "endpoint", config.get("endpoint"));
        if (config.get("args") instanceof List<?> args) {
            for (var arg : args) {
                rejectIfPlaceholder(serverName, "args", arg);
            }
        }
    }

    private static void rejectIfPlaceholder(String serverName, String field, Object value) {
        if (value != null && String.valueOf(value).contains(PLACEHOLDER_PREFIX)) {
            throw new UnsupportedSecretLocationException(serverName, field);
        }
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

    public static final class MissingSecretException extends RuntimeException {
        @Serial
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

    public static final class UnsupportedSecretLocationException extends IllegalArgumentException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String serverName;
        private final String fieldName;

        UnsupportedSecretLocationException(String serverName, String fieldName) {
            super("secret placeholders are only allowed in env and headers values");
            this.serverName = serverName;
            this.fieldName = fieldName;
        }

        public String serverName() {
            return serverName;
        }

        public String fieldName() {
            return fieldName;
        }
    }
}
