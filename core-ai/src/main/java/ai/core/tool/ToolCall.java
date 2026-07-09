package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.tool.registry.ToolExposure;
import ai.core.tool.tools.SubAgentToolCall;
import ai.core.utils.JsonSchemaUtil;
import ai.core.utils.JsonUtil;
import core.framework.util.Strings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
public abstract class ToolCall {
    public static final long DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L;
    public static final String SAVE_TO_FILE_PARAM = "save_to_file";
    static final int MAX_SAVE_TO_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    /**
     * Safely extracts a string value from parsed arguments, handling cases where
     * an LLM may have passed a nested JSON object/array instead of an escaped string.
     *
     * @param args the parsed arguments map
     * @param key  the parameter name
     * @return the string value, or null if not present; nested objects/arrays are serialized to JSON strings
     */
    public static String getStringValue(Map<String, Object> args, String key) {
        var value = args.get(key);
        if (value == null) return null;
        if (value instanceof String s) return s;
        return JsonUtil.toJson(value);
    }

    String namespace;
    String name;
    String description;
    List<ToolCallParameter> parameters;
    Boolean needAuth;
    Boolean directReturn;
    Boolean llmVisible;
    Boolean discoverable;
    String concurrencyGroup;
    String sourceType;
    protected Long timeoutMs;
    ToolExposure exposure;

    public ToolCallResult execute(String arguments, ExecutionContext context) {
        return execute(arguments);
    }

    public abstract ToolCallResult execute(String arguments);

    public ToolCallResult poll(String taskId) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support polling");
    }

    public ToolCallResult submitInput(String taskId, String input) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support user input");
    }

    public ToolCallResult cancel(String taskId) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support cancellation");
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getDescription() {
        return description;
    }

    public List<ToolCallParameter> getParameters() {
        return parameters;
    }

    public Boolean isNeedAuth() {
        return needAuth;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setParameters(List<ToolCallParameter> parameters) {
        this.parameters = parameters;
    }

    public void setNeedAuth(Boolean needAuth) {
        this.needAuth = needAuth;
    }


    public Boolean isDirectReturn() {
        return directReturn != null && directReturn;
    }

    public void setDirectReturn(Boolean directReturn) {
        this.directReturn = directReturn;
    }

    public Boolean isLlmVisible() {
        return llmVisible == null || llmVisible;
    }

    public void setLlmVisible(Boolean llmVisible) {
        this.llmVisible = llmVisible;
    }

    public ToolExposure getExposure() {
        return exposure != null ? exposure : ToolExposure.DIRECT;
    }

    public void setExposure(ToolExposure exposure) {
        this.exposure = exposure;
    }

    public boolean isDiscoverable() {
        return discoverable != null && discoverable;
    }

    /**
     * Returns the concurrency group name. Tools with the same non-null group can run concurrently
     * within the same batch. Null means exclusive execution (tool runs alone).
     */
    public String getConcurrencyGroup() {
        return concurrencyGroup;
    }

    public String getSourceType() {
        return sourceType != null ? sourceType : "builtin";
    }

    public void setDiscoverable(Boolean discoverable) {
        this.discoverable = discoverable;
    }

    public long getTimeoutMs() {
        return timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isSubAgent() {
        return false;
    }

    @Override
    public String toString() {
        return JsonUtil.toJson(toTool(null));
    }

    public Tool toTool() {
        return toTool(null);
    }

    public Tool toTool(ExecutionContext context) {
        var tool = new Tool();
        tool.type = ToolType.FUNCTION;
        var func = new Function();
        func.name = name;
        // todo: better name truncation strategy
        if (func.name.length() > 64) {
            func.name = func.name.substring(func.name.length() - 64);
            if (func.name.contains("_")) {
                func.name = func.name.substring(func.name.indexOf('_') + 1);
            }
        }
        func.description = getDescription();
        func.parameters = toJsonSchema();
        tool.function = func;
        return tool;
    }

    public JsonSchema toJsonSchema() {
        var schema = JsonSchemaUtil.toJsonSchema(parameters);
        if ("mcp".equals(sourceType) || "api".equals(sourceType) || "self-harness".equals(sourceType)) {
            if (schema.properties == null) {
                schema.properties = new LinkedHashMap<>();
            }
            if (!schema.properties.containsKey(SAVE_TO_FILE_PARAM)) {
                var param = new JsonSchema();
                param.type = JsonSchema.PropertyType.STRING;
                param.description = """
                    Save tool output to this file path instead of returning inline.
                    Returns a preview (schema + first 3 records) so you can understand the data
                    without consuming context. Use the saved file with shell scripts or Python
                    to analyze, filter, aggregate, or visualize the full result.

                    Prefer this approach whenever you need to:
                    - Process or analyze the result further (stats, filtering, grouping)
                    - Run scripts or commands on the output data
                    - Handle more than a trivial amount of data

                    If not specified, a timestamped filename is generated automatically.""";
                schema.properties.put(SAVE_TO_FILE_PARAM, param);
            }
        }
        return schema;
    }

    /**
     * Fills missing, null, or blank parameters with their declared default values.
     * Returns parameter names that remain missing (required, no default available).
     */
    public List<String> normalizeArguments(Map<String, Object> args) {
        if (parameters == null || parameters.isEmpty()) return List.of();
        var stillMissing = new ArrayList<String>();
        for (var param : parameters) {
            var value = args.get(param.getName());
            if (value == null || (value instanceof String s && s.isBlank())) {
                var def = param.getDefaultValue();
                if (def != null) {
                    args.put(param.getName(), def.get());
                } else if (Boolean.TRUE.equals(param.isRequired())) {
                    stillMissing.add(param.getName());
                }
            }
        }
        return stillMissing;
    }

    public List<String> findMissingRequiredParams(String arguments) {
        if (parameters == null || parameters.isEmpty()) return List.of();
        try {
            return findMissingRequiredParams(parseArguments(arguments));
        } catch (Exception e) {
            return List.of("(arguments is not valid JSON)");
        }
    }

    public List<String> findMissingRequiredParams(Map<String, Object> args) {
        if (parameters == null || parameters.isEmpty()) return List.of();
        var required = parameters.stream().filter(p -> Boolean.TRUE.equals(p.isRequired())).toList();
        if (required.isEmpty()) return List.of();
        return required.stream()
                .filter(p -> !args.containsKey(p.getName()) || args.get(p.getName()) == null)
                .map(ToolCallParameter::getName)
                .toList();
    }

    /**
     * Parses tool call arguments with lenient handling for string-typed fields.
     * <p>
     * Some LLMs may pass nested JSON objects/arrays as the value of a string-typed field
     * (e.g., write_file content containing JSON), instead of properly escaping it as a JSON string.
     * This method detects such cases and automatically converts the nested value to a JSON string.
     *
     * @param arguments the raw JSON arguments string from the LLM tool call
     * @return a Map of parsed arguments with string fields normalized
     */
    public Map<String, Object> parseArguments(String arguments) {
        var args = JsonUtil.toMap(arguments);
        if (parameters != null) {
            for (var param : parameters) {
                var value = args.get(param.getName());
                if (value != null && !(value instanceof String) && isStringTyped(param)) {
                    args.put(param.getName(), JsonUtil.toJson(value));
                }
            }
        }
        return args;
    }

    private boolean isStringTyped(ToolCallParameter param) {
        var type = param.getType();
        return type != null && type == ToolCallParameterType.STRING
                || type == null && param.getClassType() == String.class;
    }

    public enum ConcurrencyGroupType {
        FILE_SEARCH("FileSearch"),
        WEB_QUERY("WebQuery"),
        SHELL_COMMAND("BatchBash");
        private final String typeName;

        ConcurrencyGroupType(String name) {
            this.typeName = name;
        }

        public String getTypeName() {
            return typeName;
        }
    }

    /**
     * Whether this specific tool call (identified by its arguments) is safe to run
     * concurrently with other tools. Default is true — most tools are designed to be
     * concurrency-safe. Override to provide dynamic checking based on arguments.
     */
    public boolean isConcurrencySafe(String arguments) {
        return true;
    }

    // ── save_to_file result formatting utilities ──

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

    @SuppressWarnings("unchecked")
    private static void appendArrayPreview(StringBuilder sb, List<?> list, String schemaPath) {
        if (list.isEmpty()) {
            sb.append("(empty array)\n\n");
            sb.append("Use file tools to browse or scripts to analyze the full data.\n");
            sb.append("Schema also available at ").append(schemaPath).append('\n');
            return;
        }
        if (list.get(0) instanceof Map<?, ?> first) {
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
        sb.append("\nUse file tools to browse or scripts to analyze the full data.\n");
        sb.append("Schema also available at ").append(schemaPath).append('\n');
    }

    private static void appendObjectPreview(StringBuilder sb, Map<String, Object> map, String schemaPath, String content) {
        var fields = inferObjectFields(map);
        sb.append("Schema (").append(fields.size()).append(" fields):\n");
        for (var entry : fields.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(" : ").append(entry.getValue()).append('\n');
        }
        var preview = content;
        if (preview.length() > 800) preview = preview.substring(0, 800) + "...";
        sb.append("\nPreview:\n").append(preview).append('\n');
        sb.append("\nUse file tools to browse or scripts to analyze the full data.\n");
        sb.append("Schema also available at ").append(schemaPath).append('\n');
    }

    private static void appendTextPreview(StringBuilder sb, String content) {
        var preview = content;
        if (preview.length() > 500) preview = preview.substring(0, 500) + "...";
        sb.append("Preview:\n").append(preview).append('\n');
        sb.append("\nUse file tools to browse or scripts to analyze the full data.\n");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> inferFields(List<Map<String, Object>> items) {
        var fields = new LinkedHashMap<String, String>();
        var enumCandidates = new LinkedHashMap<String, LinkedHashSet<String>>();
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
            if (values.size() <= 20 && values.size() > 0) {
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
    private static LinkedHashSet<String> enumCandidatesFor(List<Map<String, Object>> items, String key) {
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

    public abstract static class Builder<B extends Builder<B, T>, T extends ToolCall> {
        String namespace;
        String name;
        String description;
        List<ToolCallParameter> parameters;
        Boolean needAuth;
        Boolean continueAfterSlash;
        Boolean directReturn;
        Boolean llmVisible;
        Boolean discoverable;
        String concurrencyGroup;
        String sourceType;
        Long timeoutMs;
        ToolExposure exposure;

        protected abstract B self();

        public B name(String name) {
            this.name = name;
            return self();
        }

        public B namespace(String namespace) {
            this.namespace = namespace;
            return self();
        }

        public B description(String description) {
            this.description = description;
            return self();
        }

        public B parameters(List<ToolCallParameter> parameters) {
            this.parameters = parameters;
            return self();
        }

        public B needAuth(Boolean needAuth) {
            this.needAuth = needAuth;
            return self();
        }

        public B continueAfterSlash(Boolean continueAfterSlash) {
            this.continueAfterSlash = continueAfterSlash;
            return self();
        }

        public B directReturn(Boolean directReturn) {
            this.directReturn = directReturn;
            return self();
        }

        public B llmVisible(Boolean llmVisible) {
            this.llmVisible = llmVisible;
            return self();
        }

        public B discoverable(Boolean discoverable) {
            this.discoverable = discoverable;
            return self();
        }

        public B concurrencyGroup(String concurrencyGroup) {
            this.concurrencyGroup = concurrencyGroup;
            return self();
        }

        public B sourceType(String sourceType) {
            this.sourceType = sourceType;
            return self();
        }

        public B exposure(ToolExposure exposure) {
            this.exposure = exposure;
            return self();
        }

        public B timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return self();
        }

        public void build(T toolCall) {
            if (name == null) {
                throw new RuntimeException("name is required");
            }
            if (description == null) {
                throw new RuntimeException("description is required");
            }
            if (parameters == null) {
                parameters = List.of();
            }
            toolCall.namespace = namespace;
            toolCall.name = name;
            if (toolCall instanceof SubAgentToolCall) {
                // for sub-agent tool, we put the description in the tool call description, so that it can be used as the sub-agent execution description.
                toolCall.description = Strings.format("""
                        This is a subagent tool.
                        When the agent calls this tool, it will trigger a subagent execution with the following description and parameters.
                        Here is the description of the subagent to be executed:
                        {}
                        """, description);
            } else {
                toolCall.description = description;
            }
            toolCall.parameters = parameters;
            toolCall.needAuth = needAuth != null && needAuth;
            toolCall.directReturn = directReturn != null && directReturn;
            toolCall.llmVisible = llmVisible == null || llmVisible;
            toolCall.discoverable = discoverable != null && discoverable;
            toolCall.concurrencyGroup = concurrencyGroup;
            toolCall.sourceType = sourceType;
            toolCall.timeoutMs = timeoutMs;
            toolCall.exposure = exposure;
        }
    }
}
