package ai.core.server.skill.telemetry;

import java.time.ZonedDateTime;

/**
 * Atomic increment payload for one (skillId, packageDigest) bucket.
 *
 * @author stephen
 */
public record SkillTelemetryDelta(
        String skillId,
        String packageDigest,
        long invokeDelta,
        long resourceReadDelta,
        long materializeFailDelta,
        long attributedScriptCallsDelta,
        long attributedScriptErrorsDelta,
        ZonedDateTime lastInvokedAt
) {
    public boolean isZero() {
        return invokeDelta == 0
                && resourceReadDelta == 0
                && materializeFailDelta == 0
                && attributedScriptCallsDelta == 0
                && attributedScriptErrorsDelta == 0;
    }
}
