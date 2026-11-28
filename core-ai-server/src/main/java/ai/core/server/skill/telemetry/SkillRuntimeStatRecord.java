package ai.core.server.skill.telemetry;

import java.time.ZonedDateTime;

/**
 * Per-version runtime usage and error statistics for a skill.
 *
 * @author stephen
 */
public record SkillRuntimeStatRecord(
        String skillId,
        String packageDigest,
        long invokeCount,
        long resourceReadCount,
        long materializeFailCount,
        long attributedScriptCalls,
        long attributedScriptErrors,
        ZonedDateTime lastInvokedAt,
        ZonedDateTime updatedAt
) {
}
