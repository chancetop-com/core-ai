package ai.core.tool.mcp;

import ai.core.mcp.client.MCPClientService;
import ai.core.tool.ToolCallParameter;
import io.modelcontextprotocol.kotlin.sdk.Tool;

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
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .parameters(buildParameters(tool.getInputSchema()))
                    .mcpClientService(mcpClientService).build());
        }
        return mcpTollCalls;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallParameter> buildParameters(Tool.Input inputSchema) {
        var parameters = new ArrayList<ToolCallParameter>();
        var required = inputSchema.getRequired();
        for (var entry : inputSchema.getProperties().entrySet()) {
            var value = (Map<String, Object>) entry.getValue();
            var name = entry.getKey();
            var type = (String) value.getOrDefault("type", "string");
            var title = (String) value.getOrDefault("title", name);
            var enums = (List<String>) value.getOrDefault("enums", List.of());
            var parameter = ToolCallParameter.builder()
                    .name(name)
                    .description(title)
                    .type("string".equals(type) ? String.class : Object.class)
                    .required(required != null && required.contains(name))
                    .enums(enums)
                    .build();
            parameters.add(parameter);
        }
        return parameters;
    }
}
