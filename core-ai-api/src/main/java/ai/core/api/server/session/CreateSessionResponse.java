package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateSessionResponse {
    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @Property(name = "loaded_tools")
    public List<String> loadedTools;

    @Property(name = "loaded_skills")
    public List<String> loadedSkills;

    @Property(name = "loaded_sub_agents")
    public List<String> loadedSubAgents;
}
