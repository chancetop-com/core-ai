package ai.core.server.skill.telemetry;

import java.time.ZonedDateTime;

/**
 * Stored skill papercut friction record.
 *
 * @author stephen
 */
public record SkillPapercutRecord(
        String id,
        String skillId,
        String qualifiedName,
        String packageDigest,
        String dedupKey,
        SkillPapercutCategory category,
        SkillPapercutStatus status,
        String summary,
        String latestObservedError,
        String suggestedFix,
        int occurrenceCount,
        String reporterUserId,
        String latestSessionId,
        String resolvedBy,
        ZonedDateTime resolvedAt,
        ZonedDateTime createdAt,
        ZonedDateTime lastSeenAt
) {
}
