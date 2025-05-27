package ai.core.tool.domain;

import ai.core.tool.ToolCallParameter;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ToolCallInputSchema {
    public static ToolCallInputSchema of(List<ToolCallParameter> parameters, String type) {
        var schema = new ToolCallInputSchema();
        schema.type = type;
        if (parameters == null) return schema;
        schema.properties = parameters.stream().map(ToolCallInputSchemaProperty::of).toList();
        schema.required = parameters.stream().filter(ToolCallParameter::getRequired).map(ToolCallParameter::getName).toList();
        return schema;
    }

    @Property(name = "type")
    public String type;

    @Property(name = "properties")
    public List<ToolCallInputSchemaProperty> properties;

    @Property(name = "required")
    public List<String> required;
}
