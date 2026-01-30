package ai.core.tool.mcp;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.api.mcp.schema.tool.Tool;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientService;
import ai.core.tool.ToolCallParameter;
import ai.core.utils.JsonSchemaUtil;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
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
            if (includes != null && includes.stream().noneMatch(t -> Pattern.compile(t).matcher(tool.name).matches())) continue;
            if (excludes != null && excludes.stream().anyMatch(t -> Pattern.compile(t).matcher(tool.name).matches())) continue;
            mcpToolCalls.add(buildToolCall(tool, client, serverName));
        }
    }

    private static McpToolCall buildToolCall(Tool tool, McpClientService client, String serverName) {
        return McpToolCall.builder()
                .name(tool.name)
                .namespace(serverName)
                .description(tool.description)
                .needAuth(tool.needAuth)
                .parameters(buildParameters(tool.inputSchema))
                .mcpClientService(client)
                .build();
    }

    private static List<ToolCallParameter> buildParameters(JsonSchema inputSchema) {
        var parameters = new ArrayList<ToolCallParameter>();
        var properties = inputSchema.properties;
        if (properties == null || properties.isEmpty()) return parameters;

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