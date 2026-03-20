package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class RegisterRepoSkillsResponse {
    @Property(name = "skills")
    public List<SkillDefinitionView> skills;
}
