package ai.core.tool.mcp;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientService;
import ai.core.tool.ToolCallParameter;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class McpToolCalls extends ArrayList<McpToolCall> {
    @Serial
    private static final long serialVersionUID = 2202468890851081427L;

    public static List<McpToolCall> from(McpClientManager mcpClientManager, List<String> serverNames, List<String> includes) {
        return from(mcpClientManager, serverNames, includes, null);
    }

    public static List<McpToolCall> from(McpClientManager mcpClientManager, List<String> serverNames, List<String> includes, List<String> excludes) {
        var mcpToolCalls = new McpToolCalls();
        for (var serverName : serverNames) {
            if (mcpClientManager.hasServer(serverName)) {
                addToolsFromClient(mcpToolCalls, mcpClientManager.getClient(serverName), serverName, includes, excludes);
            }
        }
        return mcpToolCalls;
    }

    private static void addToolsFromClient(List<McpToolCall> mcpToolCalls, McpClientService client, String serverName, List<String> includes, List<String> excludes) {
        var tools = client.listTools();
        for (var tool : tools) {
            if (includes != null && includes.stream().noneMatch(t -> Pattern.compile(t).matcher(tool.name()).matches())) continue;
            if (excludes != null && excludes.stream().anyMatch(t -> Pattern.compile(t).matcher(tool.name()).matches())) continue;
            mcpToolCalls.add(buildToolCall(tool, client, serverName));
        }
    }

    private static McpToolCall buildToolCall(McpSchema.Tool tool, McpClientService client, String serverName) {
        return McpToolCall.builder()
                .name(tool.name())
                .namespace(serverName)
                .description(tool.description())
                .needAuth(false)  // SDK Tool doesn't have needAuth field
                .parameters(buildParameters(tool.inputSchema()))
                .mcpClientService(client)
                .build();
    }

    private static List<ToolCallParameter> buildParameters(McpSchema.JsonSchema inputSchema) {
        if (inputSchema == null) return List.of();
        var schemaMap = jsonSchemaToMap(inputSchema);
        return buildParametersFromMap(schemaMap);
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallParameter> buildParameters(String name, Map<String, Object> property, Map<String, Object> json) {
        var type = getString(property, "type");
        var description = getString(property, "description");
        var format = getString(property, "format");
        var required = json.get("required");
        boolean isRequired = required instanceof List<?> reqList && reqList.contains(name);

        var parameter = ToolCallParameter.builder()
                .name(name)
                .description(description)
                .classType(mapSchemaType(type))
                .format(format)
                .required(isRequired)
                .enums(extractEnums(property.get("enum")))
                .build();

        if ("array".equalsIgnoreCase(type)) {
            applyArrayItemSchema(parameter, property);
        }
        return List.of(parameter);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonSchemaToMap(McpSchema.JsonSchema jsonSchema) {
        var map = new HashMap<String, Object>();
        map.put("type", jsonSchema.type());
        map.put("properties", jsonSchema.properties());
        map.put("required", jsonSchema.required());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallParameter> buildParametersFromMap(Map<String, Object> schemaMap) {
        var parameters = new ArrayList<ToolCallParameter>();
        var properties = (Map<String, Object>) schemaMap.get("properties");
        if (properties == null || properties.isEmpty()) return parameters;
        for (var entry : properties.entrySet()) {
            var propValue = entry.getValue();
            if (propValue instanceof Map<?, ?> propMap) {
                var prop = (Map<String, Object>) propMap;
                parameters.addAll(buildParameters(entry.getKey(), prop, schemaMap));
            }
        }
        return parameters;
    }

    private static List<String> extractEnums(Object enumValue) {
        if (!(enumValue instanceof List<?> enumList)) return null;
        var enums = new ArrayList<String>();
        for (var e : enumList) {
            if (e != null) enums.add(e.toString());
        }
        return enums.isEmpty() ? null : enums;
    }

    @SuppressWarnings("unchecked")
    private static void applyArrayItemSchema(ToolCallParameter parameter, Map<String, Object> property) {
        var itemsValue = property.get("items");
        if (!(itemsValue instanceof Map<?, ?> itemsMap)) return;
        var items = (Map<String, Object>) itemsMap;
        var itemType = getString(items, "type");
        parameter.setItemType(mapSchemaType(itemType));
        var itemEnums = extractEnums(items.get("enum"));
        if (itemEnums != null) parameter.setItemEnums(itemEnums);
        if ("object".equalsIgnoreCase(itemType)) {
            parameter.setItems(buildParametersFromMap(items));
        }
    }
    
    private static String getString(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value != null ? value.toString() : null;
    }
    
    private static Class<?> mapSchemaType(String type) {
        if (type == null) return Object.class;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string" -> String.class;
            case "number" -> Double.class;
            case "integer" -> Long.class;
            case "boolean" -> Boolean.class;
            case "array" -> List.class;
            case "object" -> Map.class;
            default -> Object.class;
        };
    }
}
