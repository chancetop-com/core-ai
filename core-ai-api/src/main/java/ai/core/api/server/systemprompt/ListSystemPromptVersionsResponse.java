package ai.core.api.server.systemprompt;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSystemPromptVersionsResponse {
    @Property(name = "versions")
    public List<SystemPromptVersionView> versions;
}
