package ai.core.tool.mcp;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.mcp.client.McpClientService;
import ai.core.utils.JsonSchemaUtil;
import ai.core.tool.ToolCallParameter;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class McpToolCalls extends ArrayList<McpToolCall> {
    @Serial
    private static final long serialVersionUID = 2202468890851081427L;

    public static List<McpToolCall> from(McpClientService mcpClientService, List<String> includes) {
        var mcpTools = from(mcpClientService);
        if (includes == null || includes.isEmpty()) {
            return mcpTools;
        }
        return mcpTools.stream().filter(tool -> includes.stream().anyMatch(n -> n.contains(tool.getName()))).toList();
    }

    public static List<McpToolCall> from(McpClientService mcpClientService) {
        var tools = mcpClientService.listTools();
        var mcpTollCalls = new McpToolCalls();
        for (var tool : tools) {
            mcpTollCalls.add(McpToolCall.builder()
                    .name(tool.name)
                    .description(tool.description)
                    .needAuth(tool.needAuth)
                    .parameters(buildParameters(tool.inputSchema))
                    .mcpClientService(mcpClientService).build());
        }
        return mcpTollCalls;
    }

    private static List<ToolCallParameter> buildParameters(JsonSchema inputSchema) {
        var parameters = new ArrayList<ToolCallParameter>();
        var properties = inputSchema.properties;

        for (var entry : properties.entrySet()) {
            parameters.addAll(buildParameters(entry.getKey(), entry.getValue(), inputSchema));
        }
        return parameters;
    }

    public static List<ToolCallParameter> buildParameters(String name, JsonSchema property, JsonSchema json) {
        var parameter = ToolCallParameter.builder()
                .name(name)
                .description(property.description)
                .classType(JsonSchemaUtil.mapType(property.type))
                .format(property.format)
                .required(json.required != null && json.required.contains(name))
                .enums(property.enums)
                .build();
        if (property.type == JsonSchema.PropertyType.ARRAY) {
            parameter.setItemType(JsonSchemaUtil.mapType(property.items.type));
            if (property.items.enums != null && !property.items.enums.isEmpty()) {
                parameter.setItemEnums(property.items.enums);
            }
            if (property.items.type == JsonSchema.PropertyType.OBJECT) {
                parameter.setItems(buildParameters(property.items));
            }
        }
        return List.of(parameter);
    }
}