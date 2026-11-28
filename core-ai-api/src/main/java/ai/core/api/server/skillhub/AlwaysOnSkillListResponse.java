package ai.core.api.server.skillhub;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * Response body for GET /api/skill-hub/always-on.
 *
 * @author stephen
 */
public class AlwaysOnSkillListResponse {
    @NotNull
    @Property(name = "skills")
    public List<AlwaysOnSkillView> skills;
}
