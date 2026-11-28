package ai.core.server.skill.telemetry;

import java.util.Locale;

/**
 * Lifecycle state of a skill papercut ticket.
 *
 * @author stephen
 */
public enum SkillPapercutStatus {
    OPEN,
    RESOLVED,
    IGNORED;

    public static SkillPapercutStatus parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (var value : values()) {
            if (value.name().equals(normalized)) return value;
        }
        return null;
    }
}
