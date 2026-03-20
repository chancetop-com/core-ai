package ai.core.api.server.skill;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateSkillRequest {
    @Property(name = "description")
    public String description;
}
