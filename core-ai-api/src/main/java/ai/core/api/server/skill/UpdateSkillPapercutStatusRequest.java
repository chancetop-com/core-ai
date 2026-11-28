package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Request body for PUT /api/skills/:id/papercuts/:papercutId.
 *
 * @author stephen
 */
public class UpdateSkillPapercutStatusRequest {
    @NotNull
    @Property(name = "status")
    public SkillPapercutStatusView status;
}
