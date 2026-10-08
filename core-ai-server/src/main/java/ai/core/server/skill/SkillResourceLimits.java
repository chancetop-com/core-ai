package ai.core.server.skill;

/**
 * Storage policy limits for skill resources; the hub JSON endpoints share the inline cap.
 *
 * @author stephen
 */
public final class SkillResourceLimits {
    /** UTF-8 text at or below this size stays inline in the skill document. */
    public static final long MAX_INLINE_RESOURCE_BYTES = 1024 * 1024;
    /** Write-time budget for the inline part of a skill document (SKILL.md + inline resources). */
    public static final long MAX_INLINE_DOC_BYTES = 12 * 1024 * 1024;
    /** Hard cap for a single resource; larger files are rejected instead of shipped through the archive. */
    public static final long MAX_RESOURCE_BYTES = 32 * 1024 * 1024;

    private SkillResourceLimits() {
    }
}
