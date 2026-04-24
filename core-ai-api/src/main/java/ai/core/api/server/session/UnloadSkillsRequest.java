package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author xander
 */
public class UnloadSkillsRequest {
    @NotNull
    @Property(name = "skill_ids")
    public List<String> skillIds = List.of();
}
