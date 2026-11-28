package ai.core.api.server.skill;

import core.framework.api.json.Property;

/**
 * API view mirror of SkillTier, kept separate from the persistence enum to satisfy core-ng validation.
 *
 * @author stephen
 */
public enum SkillTierView {
    @Property(name = "IMPORTED")
    IMPORTED,
    @Property(name = "DOMAIN")
    DOMAIN,
    @Property(name = "CORE")
    CORE,
    @Property(name = "ALWAYS_ON")
    ALWAYS_ON
}
