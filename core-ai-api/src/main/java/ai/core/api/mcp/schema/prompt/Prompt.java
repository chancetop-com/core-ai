package ai.core.api.mcp.schema.prompt;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class Prompt {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "arguments")
    public List<PromptArgument> arguments;
}
