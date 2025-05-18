package ai.core.api.mcp.schema.prompt;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class PromptArgument {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "required")
    public Boolean required;
}
