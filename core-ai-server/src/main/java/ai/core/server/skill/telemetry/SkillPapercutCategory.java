package ai.core.server.skill.telemetry;

import java.util.Locale;

/**
 * Friction categories reported against an attached skill.
 *
 * @author stephen
 */
public enum SkillPapercutCategory {
    BROKEN_COMMAND,
    MISSING_PERMISSION,
    OUTDATED_DOC,
    AMBIGUOUS_STEP,
    PLATFORM_INCOMPATIBLE,
    TRIGGER_MISS,
    RESOURCE_READ_FAIL,
    SCRIPT_ERROR,
    OUTPUT_UNUSABLE,
    OTHER;

    public static SkillPapercutCategory parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (var value : values()) {
            if (value.name().equals(normalized)) return value;
        }
        return null;
    }
}
