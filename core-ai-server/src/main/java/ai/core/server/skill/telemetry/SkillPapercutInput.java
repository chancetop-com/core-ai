package ai.core.server.skill.telemetry;

/**
 * Input for creating or deduplicating a skill papercut record.
 *
 * @author stephen
 */
public record SkillPapercutInput(
        String skillId,
        String qualifiedName,
        String packageDigest,
        SkillPapercutCategory category,
        String summary,
        String observedError,
        String suggestedFix,
        String reporterUserId,
        String sessionId
) {
}
