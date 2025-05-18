package ai.core.api.mcp.schema.prompt;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListPromptsResult {
    @Property(name = "prompts")
    public List<Prompt> prompts;

    @Property(name = "nextCursor")
    public String nextCursor;
}
