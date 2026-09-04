package ai.core.server.skillhub;

import ai.core.server.domain.SkillDefinition;

/**
 * Skill-level access decisions for the Skill Hub surface. P0 is a pass-through: the
 * route-level {@code skill.view} permission already covers readers and skills are a
 * shared catalog with no secret content. P1 adds {@code visibility} (public/namespace/
 * private) and the API-user {@code skill} resource whitelist here without touching the
 * web layer.
 *
 * @author stephen
 */
public class SkillHubAccessPolicy {
    /** P0: every skill visible to any caller that passed {@code skill.view}. */
    public boolean canView(SkillDefinition skill) {
        return true;
    }
}
