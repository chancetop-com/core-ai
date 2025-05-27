package ai.core.tool.domain;

import ai.core.tool.ToolCall;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ToolCallDTO {
    public static ToolCallDTO of(ToolCall function) {
        var domain = new ToolCallDTO();
        domain.name = function.getName();
        domain.description = function.getDescription();
        domain.inputSchema = ToolCallInputSchema.of(function.getParameters(), "object");
        return domain;
    }

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "input_schema")
    public ToolCallInputSchema inputSchema;
}
