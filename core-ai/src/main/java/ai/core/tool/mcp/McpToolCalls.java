package ai.core.tool.mcp;

import ai.core.api.mcp.JsonSchema;
import ai.core.mcp.client.McpClientService;
import ai.core.utils.JsonSchemaHelper;
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

    public static McpToolCalls from(McpClientService mcpClientService) {
        var tools = mcpClientService.listTools();
        var mcpTollCalls = new McpToolCalls();
        for (var tool : tools) {
            mcpTollCalls.add(McpToolCall.builder()
                    .name(tool.name)
                    .description(tool.description)
                    .parameters(buildParameters(tool.inputSchema))
                    .mcpClientService(mcpClientService).build());
        }
        return mcpTollCalls;
    }

    private static List<ToolCallParameter> buildParameters(JsonSchema inputSchema) {
        var parameters = new ArrayList<ToolCallParameter>();
        var required = inputSchema.required;
        var properties = inputSchema.properties;

        for (var entry : properties.entrySet()) {
            var value = entry.getValue();
            var name = entry.getKey();
            var type = value.type;
//            var enums = value.enums;
            var description = value.description;

            var parameter = ToolCallParameter.builder()
                    .name(name)
                    .description(description)
                    .type(JsonSchemaHelper.mapType(type))
                    .format(value.format)
                    .required(required != null && required.contains(name))
//                    .enums(enums)
                    .build();
            parameters.add(parameter);
        }
        return parameters;
    }
}