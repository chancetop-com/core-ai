package ai.core.api.server.prompt;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListPromptsResponse {
    @Property(name = "prompts")
    public List<PromptTemplateView> prompts;
}
