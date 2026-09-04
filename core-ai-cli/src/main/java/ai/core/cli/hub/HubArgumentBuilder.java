package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merges call arguments from {@code --args JSON}, {@code --args-file} content and
 * repeatable {@code --arg key=value} assignments (later sources win), then coerces
 * {@code --arg} strings to the types declared by the tool input schema.
 * <p>
 * The server owns argument validation — this builder only does best-effort coercion;
 * values that fail to convert stay strings with a warning on stderr.
 *
 * @author stephen
 */
public class HubArgumentBuilder {
    private static final TypeReference<List<Object>> JSON_LIST = new TypeReference<>() {
    };

    public String build(String argsJson, String argsFileJson, List<String> assignments, String inputSchemaJson) {
        var merged = new LinkedHashMap<String, Object>();
        if (argsJson != null && !argsJson.isBlank()) merged.putAll(parseObject(argsJson, "--args"));
        if (argsFileJson != null && !argsFileJson.isBlank()) merged.putAll(parseObject(argsFileJson, "--args-file"));
        if (assignments != null && !assignments.isEmpty()) {
            var schema = parseSchema(inputSchemaJson);
            for (var assignment : assignments) {
                int separator = assignment.indexOf('=');
                if (separator <= 0) {
                    throw new HubCliError(HubExitCodes.USAGE,
                            "--arg must be key=value, got: " + assignment);
                }
                var name = assignment.substring(0, separator).trim();
                var raw = assignment.substring(separator + 1);
                merged.put(name, coerce(name, raw, schema));
            }
        }
        return JsonUtil.toJson(merged);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseSchema(String inputSchemaJson) {
        var types = new LinkedHashMap<String, String>();
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) return types;
        try {
            var schema = JsonUtil.fromJson(Map.class, inputSchemaJson);
            var properties = (Map<String, Object>) schema.get("properties");
            if (properties == null) return types;
            for (var entry : properties.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> property)) continue;
                var type = property.get("type");
                if (type != null) types.put(entry.getKey(), String.valueOf(type));
            }
        } catch (RuntimeException ignored) {
            // unreadable schema: fall back to plain strings for --arg values
        }
        return types;
    }

    private Object coerce(String name, String raw, Map<String, String> schema) {
        var type = schema.get(name);
        if (type == null) return raw;
        try {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "boolean" -> coerceBoolean(raw);
                case "integer" -> Long.valueOf(raw);
                case "number" -> Double.valueOf(raw);
                case "object" -> JsonUtil.fromJson(Map.class, raw);
                case "array" -> JsonUtil.fromJson(JSON_LIST, raw);
                default -> raw;
            };
        } catch (RuntimeException e) {
            ConsoleWriter.printError("warning: could not parse --arg " + name + "=" + raw
                    + " as " + type + "; sending as string");
            return raw;
        }
    }

    private Object coerceBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.valueOf(raw);
        }
        throw new IllegalArgumentException("not a boolean");
    }

    private Map<String, Object> parseObject(String json, String source) {
        try {
            return JsonUtil.toMap(json);
        } catch (RuntimeException e) {
            throw new HubCliError(HubExitCodes.USAGE,
                    source + " must be a valid JSON object: " + e.getMessage(), e);
        }
    }
}
