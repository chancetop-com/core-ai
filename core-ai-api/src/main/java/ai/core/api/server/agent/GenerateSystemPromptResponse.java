package ai.core.api.server.agent;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GenerateSystemPromptResponse {
    @Property(name = "system_prompt")
    public String systemPrompt;
}
