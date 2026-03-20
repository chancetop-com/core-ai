package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class LoadSkillsResponse {
    @Property(name = "loaded_skills")
    public List<String> loadedSkills;
}
