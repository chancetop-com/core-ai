package ai.core.tool.domain;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ToolCallInputSchema {
    @Property(name = "type")
    public String type;

    @Property(name = "properties")
    public List<ToolCallInputSchemaProperty> properties;

    @Property(name = "required")
    public List<String> required;
}
