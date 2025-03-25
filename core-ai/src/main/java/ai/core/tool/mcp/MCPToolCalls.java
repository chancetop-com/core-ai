package ai.core.tool.mcp;

import ai.core.mcp.client.MCPClientService;
import ai.core.tool.ToolCallParameter;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class MCPToolCalls extends ArrayList<MCPToolCall> {
    @Serial
    private static final long serialVersionUID = 2202468890851081427L;

    public static MCPToolCalls from(MCPClientService mcpClientService) {
        var tools = mcpClientService.listTools();
        var mcpTollCalls = new MCPToolCalls();
        for (var tool : tools) {
            mcpTollCalls.add(MCPToolCall.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(buildParameters(tool.inputSchema()))
                    .mcpClientService(mcpClientService).build());
        }
        return mcpTollCalls;
    }

    @SuppressWarnings("rawtypes")
    private static List<ToolCallParameter> buildParameters(McpSchema.JsonSchema inputSchema) {
        var parameters = new ArrayList<ToolCallParameter>();
        var required = inputSchema.required();
        var properties = inputSchema.properties();

        for (var entry : properties.entrySet()) {
            var propValue = entry.getValue();

            if (propValue instanceof Map propObj) {
                var name = entry.getKey();
                var type = (String) propObj.get("type");
                var enums = getListValue(propObj.get("enum"));
                var description = (String) propObj.get("description");

                var parameter = ToolCallParameter.builder()
                        .name(name)
                        .description(description)
                        .type(mapType(type))
                        .required(required != null && required.contains(name))
                        .enums(enums)
                        .build();
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    @SuppressWarnings("rawtypes")
    private static List<String> getListValue(Object element) {
        var enumValues = new ArrayList<String>();
        if (element instanceof List jsonArray) {
            for (var jsonElement : jsonArray) {
                enumValues.add((String) jsonElement);
            }
        }
        return enumValues;
    }

    private static Class<?> mapType(String typeStr) {
        if ("string".equalsIgnoreCase(typeStr)) {
            return String.class;
        }
        return Object.class;
    }
}