package ai.core.skill.telemetry;

import java.time.ZonedDateTime;

/**
 * Drained counter snapshot for a single skill version bucket.
 *
 * @author stephen
 */
public record SkillTelemetrySample(
        SkillTelemetryIdentity identity,
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
