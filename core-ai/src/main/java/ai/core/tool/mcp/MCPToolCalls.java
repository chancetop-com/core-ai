package ai.core.tool.mcp;

import ai.core.mcp.client.MCPClientService;
import ai.core.tool.ToolCallParameter;
import io.modelcontextprotocol.kotlin.sdk.Tool;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonPrimitive;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

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
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .parameters(buildParameters(tool.getInputSchema()))
                    .mcpClientService(mcpClientService).build());
        }
        return mcpTollCalls;
    }

    private static List<ToolCallParameter> buildParameters(Tool.Input inputSchema) {
        var parameters = new ArrayList<ToolCallParameter>();
        var required = inputSchema.getRequired();
        var properties = inputSchema.getProperties();

        for (var entry : properties.entrySet()) {
            var propValue = entry.getValue();

            if (propValue instanceof JsonObject propObj) {
                var name = entry.getKey();
                var type = getStringValue(propObj.get("type"));
                var enums = getListValue(propObj.get("enum"));
                var title = getStringValue(propObj.get("title"));

                var parameter = ToolCallParameter.builder()
                        .name(name)
                        .description(title)
                        .type(mapType(type))
                        .required(required != null && required.contains(name))
                        .enums(enums)
                        .build();
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    private static List<String> getListValue(JsonElement element) {
        var enumValues = new ArrayList<String>();
        if (element instanceof JsonArray jsonArray) {
            for (var jsonElement : jsonArray) {
                if (jsonElement instanceof JsonPrimitive) {
                    enumValues.add(((JsonPrimitive) jsonElement).getContent());
                }
            }
        }
        return enumValues;
    }

    private static String getStringValue(JsonElement element) {
        if (element instanceof JsonPrimitive) {
            return ((JsonPrimitive) element).getContent();
        }
        return null;
    }

    private static Class<?> mapType(String typeStr) {
        if ("string".equalsIgnoreCase(typeStr)) {
            return String.class;
        }
        return Object.class;
    }
}