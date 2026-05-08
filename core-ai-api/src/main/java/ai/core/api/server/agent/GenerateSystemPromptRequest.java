package ai.core.api.server.agent;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GenerateSystemPromptRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;
}
