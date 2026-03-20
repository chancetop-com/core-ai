package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSkillsResponse {
    @Property(name = "skills")
    public List<SkillDefinitionView> skills;

    @Property(name = "total")
    public Long total;
}
