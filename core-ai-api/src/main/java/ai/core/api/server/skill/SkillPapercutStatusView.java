package ai.core.api.server.skill;

import core.framework.api.json.Property;

/**
 * API view mirror of SkillPapercutStatus.
 *
 * @author stephen
 */
public enum SkillPapercutStatusView {
    @Property(name = "OPEN")
    OPEN,
    @Property(name = "RESOLVED")
    RESOLVED,
    @Property(name = "IGNORED")
    IGNORED
}
