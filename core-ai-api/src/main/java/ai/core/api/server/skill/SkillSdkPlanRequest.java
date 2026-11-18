package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author core-ai
 */
public class SkillSdkPlanRequest {
    @NotNull
    @Property(name = "skill_ids")
    public List<String> skillIds = List.of();
}
