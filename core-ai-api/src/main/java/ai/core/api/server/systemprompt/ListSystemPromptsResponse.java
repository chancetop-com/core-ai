package ai.core.api.server.systemprompt;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSystemPromptsResponse {
    @Property(name = "prompts")
    public List<SystemPromptView> prompts;
}
