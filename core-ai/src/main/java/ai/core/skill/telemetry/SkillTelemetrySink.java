package ai.core.skill.telemetry;

/**
 * Callback interface for recording runtime skill usage and attributed script execution events.
 *
 * @author stephen
 */
public interface SkillTelemetrySink {
    SkillTelemetrySink NOOP = new NoopSink();

    void recordInvoke(SkillTelemetryIdentity identity);

    void recordResourceRead(SkillTelemetryIdentity identity);

    void recordMaterializeFail(SkillTelemetryIdentity identity);

    void recordAttributedScriptCall(SkillTelemetryIdentity identity, boolean error);

    void recordUnattributedScriptError();

    final class NoopSink implements SkillTelemetrySink {
        @Override
        public void recordInvoke(SkillTelemetryIdentity identity) {
        }

        @Override
        public void recordResourceRead(SkillTelemetryIdentity identity) {
        }

        @Override
        public void recordMaterializeFail(SkillTelemetryIdentity identity) {
        }

        @Override
        public void recordAttributedScriptCall(SkillTelemetryIdentity identity, boolean error) {
        }

        @Override
        public void recordUnattributedScriptError() {
        }
    }
}
