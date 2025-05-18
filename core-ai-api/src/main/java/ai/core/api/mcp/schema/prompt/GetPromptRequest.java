package ai.core.api.mcp.schema.prompt;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GetPromptRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "arguments")
    public String arguments;
}
