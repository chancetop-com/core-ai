package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author xander
 */
public class UnloadSkillsResponse {
    @Property(name = "remaining_skills")
    public List<String> remainingSkills;
}
