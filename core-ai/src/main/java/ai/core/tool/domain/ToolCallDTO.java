package ai.core.tool.domain;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ToolCallDTO {
    public static ToolCallDTO of(ToolCall function) {
        var domain = new ToolCallDTO();
        domain.name = function.getName();
        domain.description = function.getDescription();
        domain.inputSchema = new ToolCallInputSchema();
        domain.inputSchema.type = "object";
        domain.inputSchema.properties = function.getParameters().stream().map(ToolCallInputSchemaProperty::of).toList();
        domain.inputSchema.required = function.getParameters().stream().filter(ToolCallParameter::getRequired).map(ToolCallParameter::getName).toList();
        return domain;
    }

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "input_schema")
    public ToolCallInputSchema inputSchema;
}
