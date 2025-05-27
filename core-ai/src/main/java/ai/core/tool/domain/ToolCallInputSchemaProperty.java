package ai.core.tool.domain;

import ai.core.api.mcp.JsonSchema;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.utils.JsonSchemaHelper;
import core.framework.api.json.Property;

import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
public class ToolCallInputSchemaProperty {
    public static ToolCallInputSchemaProperty of(ToolCallParameter toolCallParameter) {
        var domain = new ToolCallInputSchemaProperty();
        domain.name = toolCallParameter.getName();
        domain.description = toolCallParameter.getDescription();
        domain.type = JsonSchemaHelper.buildJsonSchemaType(toolCallParameter.getType());
        domain.required = toolCallParameter.getRequired();
        domain.enums = toolCallParameter.getEnums();
        if (domain.type == JsonSchema.PropertyType.ARRAY) {
            domain.items = ToolCallInputSchema.of(toolCallParameter.getItems(), toType(toolCallParameter.getItemType()));
        }
        return domain;
    }

    public static String toType(Class<?> itemType) {
        var basic = ToolCallParameterType.getByType(itemType).basicType();
        return basic ? JsonSchemaHelper.buildJsonSchemaType(itemType).name().toLowerCase(Locale.ROOT) : "object";
    }

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "type")
    public JsonSchema.PropertyType type;

    @Property(name = "required")
    public Boolean required;

    @Property(name = "enums")
    public List<String> enums;

    @Property(name = "items")
    public ToolCallInputSchema items;
}
