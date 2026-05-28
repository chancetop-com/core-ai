package ai.core.api.server.utils;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GenerateRequest {
    @Property(name = "system_prompt")
    public String systemPrompt;

    @Property(name = "user_prompt")
    public String userPrompt;
}
