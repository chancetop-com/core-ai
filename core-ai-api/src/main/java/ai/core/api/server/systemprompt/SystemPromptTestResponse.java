package ai.core.api.server.systemprompt;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SystemPromptTestResponse {
    @Property(name = "output")
    public String output;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "resolvedPrompt")
    public String resolvedPrompt;
}
