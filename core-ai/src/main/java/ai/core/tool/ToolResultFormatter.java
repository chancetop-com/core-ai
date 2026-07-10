package ai.core.tool;

import ai.core.utils.JsonUtil;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public final class ToolResultFormatter {

    public static String buildSaveResultMessage(String content, String filePath) {
        var parsed = tryParseJson(content);
        var schemaPath = filePath.replaceAll("\\.json$", "") + ".schema.json";
        var sb = new StringBuilder(512);
        sb.append("Result saved to ").append(filePath)
          .append(" (").append(formatSize(content.length()));
        if (parsed instanceof List<?> list) {
            sb.append(", ").append(list.size()).append(" records)\n\n");
            appendArrayPreview(sb, list, schemaPath);
        } else if (parsed instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>) map;
            sb.append(", JSON object)\n\n");
            appendObjectPreview(sb, m, schemaPath, content);
        } else {
            sb.append(")\n\n");
            appendTextPreview(sb, content);
        }
        return sb.toString();
    }

    public static String buildSchemaJson(String content) {
        var parsed = tryParseJson(content);
        if (parsed instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) list;
            var schema = new LinkedHashMap<String, Object>();
            schema.put("type", "array");
            schema.put("items", Map.of("type", "object", "properties", inferFieldSchemas(items)));
            return JsonUtil.toJson(schema);
        }
        if (parsed instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>) map;
            var schema = new LinkedHashMap<String, Object>();
            schema.put("type", "object");
            schema.put("properties", inferObjectFieldSchemas(m));
            return JsonUtil.toJson(schema);
        }
        return null;
    }

    private static Object tryParseJson(String content) {
        try {
            return JsonUtil.fromJson(Object.class, content);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    @SuppressWarnings({"unchecked", "PMD.ConsecutiveLiteralAppends"})
    private static void appendArrayPreview(StringBuilder sb, List<?> list, String schemaPath) {
        if (list.isEmpty()) {
            sb.append("(empty array)\n\nUse file tools to browse or scripts to analyze the full data.\n")
              .append("Schema also available at ").append(schemaPath).append('\n');
            return;
        }
        if (list.get(0) instanceof Map<?, ?>) {
            var items = (List<Map<String, Object>>) list;
            var fields = inferFields(items);
            sb.append("Schema (").append(fields.size()).append(" fields):\n");
            for (var entry : fields.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(" : ").append(entry.getValue()).append('\n');
            }
            sb.append("\nPreview (first ").append(Math.min(list.size(), 3)).append(" records):\n[\n");
            int count = 0;
            for (var item : items) {
                if (count++ >= 3) break;
                var json = JsonUtil.toJson(item);
                if (json.length() > 200) json = json.substring(0, 200) + "...";
                sb.append("  ").append(json).append(",\n");
            }
            sb.append("]\n");
        } else {
            sb.append("Preview (first 3 items):\n");
            int count = 0;
            for (var item : list) {
                if (count++ >= 3) break;
                var s = String.valueOf(item);
                if (s.length() > 200) s = s.substring(0, 200) + "...";
                sb.append("  ").append(s).append('\n');
            }
        }
        sb.append("\nUse file tools to browse or scripts to analyze the full data.\n")
          .append("Schema also available at ").append(schemaPath).append('\n');
    }

    @SuppressWarnings("PMD.ConsecutiveLiteralAppends")
    private static void appendObjectPreview(StringBuilder sb, Map<String, Object> map, String schemaPath, String content) {
        var fields = inferObjectFields(map);
        sb.append("Schema (").append(fields.size()).append(" fields):\n");
        for (var entry : fields.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(" : ").append(entry.getValue()).append('\n');
        }
        var preview = content;
        if (preview.length() > 800) preview = preview.substring(0, 800) + "...";
        sb.append("\nPreview:\n").append(preview).append('\n')
          .append("\nUse file tools to browse or scripts to analyze the full data.\n")
          .append("Schema also available at ").append(schemaPath).append('\n');
    }

    private static void appendTextPreview(StringBuilder sb, String content) {
        var preview = content;
        if (preview.length() > 500) preview = preview.substring(0, 500) + "...";
        sb.append("Preview:\n").append(preview).append("\n\nUse file tools to browse or scripts to analyze the full data.\n");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> inferFields(List<Map<String, Object>> items) {
        var fields = new LinkedHashMap<String, String>();
        Map<String, Set<String>> enumCandidates = new LinkedHashMap<>();
        int maxSample = Math.min(items.size(), 100);
        for (int i = 0; i < maxSample; i++) {
            for (var entry : items.get(i).entrySet()) {
                var key = entry.getKey();
                var value = entry.getValue();
                if (value == null) continue;
                var type = describeType(value);
                var existing = fields.get(key);
                if (existing == null) {
                    fields.put(key, type);
                } else if (!existing.equals(type) && !isNumericType(existing) && !isNumericType(type)) {
                    fields.put(key, "mixed");
                }
                if (value instanceof String s && !s.isBlank()) {
                    enumCandidates.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(s);
                }
            }
        }
        for (var entry : enumCandidates.entrySet()) {
            var values = entry.getValue();
            if (values.size() <= 20 && !values.isEmpty()) {
                var existing = fields.get(entry.getKey());
                if ("string".equals(existing)) {
                    fields.put(entry.getKey(), "string  " + values);
                }
            }
        }
        return fields;
    }

    private static Map<String, String> inferObjectFields(Map<String, Object> map) {
        var fields = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            var value = entry.getValue();
            fields.put(entry.getKey(), value == null ? "null" : describeType(value));
        }
        return fields;
    }

    private static Map<String, Object> inferFieldSchemas(List<Map<String, Object>> items) {
        var schemas = new LinkedHashMap<String, Object>();
        var typeMap = inferFields(items);
        for (var entry : typeMap.entrySet()) {
            var key = entry.getKey();
            var desc = entry.getValue();
            var fieldSchema = new LinkedHashMap<String, Object>();
            if (desc.startsWith("string  ")) {
                fieldSchema.put("type", "string");
                fieldSchema.put("enum", List.copyOf(enumCandidatesFor(items, key)));
            } else {
                fieldSchema.put("type", desc);
            }
            schemas.put(key, fieldSchema);
        }
        return schemas;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> enumCandidatesFor(List<Map<String, Object>> items, String key) {
        var values = new LinkedHashSet<String>();
        int maxSample = Math.min(items.size(), 100);
        for (int i = 0; i < maxSample; i++) {
            var value = items.get(i).get(key);
            if (value instanceof String s && !s.isBlank()) {
                values.add(s);
            }
        }
        return values;
    }

    private static Map<String, Object> inferObjectFieldSchemas(Map<String, Object> map) {
        var schemas = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            var fieldSchema = new LinkedHashMap<String, Object>();
            fieldSchema.put("type", entry.getValue() == null ? "null" : describeType(entry.getValue()));
            schemas.put(entry.getKey(), fieldSchema);
        }
        return schemas;
    }

    private static String describeType(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Integer || value instanceof Long) return "integer";
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List) return "array";
        if (value instanceof Map) return "object";
        return value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static boolean isNumericType(String type) {
        return "integer".equals(type) || "number".equals(type);
    }
}
