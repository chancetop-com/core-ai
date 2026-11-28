package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Request body for PUT /api/admin/skills/:id/tier.
 *
 * @author stephen
 */
public class UpdateSkillTierRequest {
    @NotNull
    @Property(name = "tier")
    public SkillTierView tier;

    @Property(name = "reason")
    public String reason;
}
