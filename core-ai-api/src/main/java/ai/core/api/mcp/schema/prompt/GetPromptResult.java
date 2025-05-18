package ai.core.api.mcp.schema.prompt;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class GetPromptResult {
    @Property(name = "description")
    public String description;

    @Property(name = "messages")
    public List<PromptMessage> messages;
}
