package ai.core.api.mcp.schema.prompt;

import ai.core.api.mcp.schema.Role;
import ai.core.api.mcp.schema.tool.Content;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class PromptMessage {
    @Property(name = "role")
    public Role role;

    @Property(name = "content")
    public Content content;
}
