package ai.core.api.server.skill;

import core.framework.api.json.Property;

/**
 * API view mirror of SkillPapercutCategory.
 *
 * @author stephen
 */
public enum SkillPapercutCategoryView {
    @Property(name = "BROKEN_COMMAND")
    BROKEN_COMMAND,
    @Property(name = "MISSING_PERMISSION")
    MISSING_PERMISSION,
    @Property(name = "OUTDATED_DOC")
    OUTDATED_DOC,
    @Property(name = "AMBIGUOUS_STEP")
    AMBIGUOUS_STEP,
    @Property(name = "PLATFORM_INCOMPATIBLE")
    PLATFORM_INCOMPATIBLE,
    @Property(name = "TRIGGER_MISS")
    TRIGGER_MISS,
    @Property(name = "RESOURCE_READ_FAIL")
    RESOURCE_READ_FAIL,
    @Property(name = "SCRIPT_ERROR")
    SCRIPT_ERROR,
    @Property(name = "OUTPUT_UNUSABLE")
    OUTPUT_UNUSABLE,
    @Property(name = "OTHER")
    OTHER
}
