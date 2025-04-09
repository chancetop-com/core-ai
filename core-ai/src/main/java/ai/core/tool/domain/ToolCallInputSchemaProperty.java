package ai.core.tool.domain;

import ai.core.tool.ToolCallParameter;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ToolCallInputSchemaProperty {
    public static ToolCallInputSchemaProperty of(ToolCallParameter toolCallParameter) {
        var domain = new ToolCallInputSchemaProperty();
        domain.name = toolCallParameter.getName();
        domain.description = toolCallParameter.getDescription();
        domain.type = toolCallParameter.getType().getName();
        domain.required = toolCallParameter.getRequired();
        domain.enums = toolCallParameter.getEnums();
        return domain;
    }

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "type")
    public String type;

    private Boolean required = Boolean.FALSE;

    private List<String> enums = List.of();

    public Boolean getRequired() {
        return required;
    }

    public List<String> getEnums() {
        return enums;
    }
}
