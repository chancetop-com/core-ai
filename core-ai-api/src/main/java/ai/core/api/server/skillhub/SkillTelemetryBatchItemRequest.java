package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Single skill usage delta inside POST /api/skill-hub/telemetry/batch.
 *
 * @author stephen
 */
public class SkillTelemetryBatchItemRequest {
    @Property(name = "skill_id")
    public String skillId;

    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "package_digest")
    public String packageDigest;

    @Property(name = "invoke_delta")
    public Long invokeDelta;

    @Property(name = "resource_read_delta")
    public Long resourceReadDelta;

    @Property(name = "materialize_fail_delta")
    public Long materializeFailDelta;

    @Property(name = "attributed_script_calls_delta")
    public Long attributedScriptCallsDelta;

    @Property(name = "attributed_script_errors_delta")
    public Long attributedScriptErrorsDelta;

    @Property(name = "last_invoked_at")
    public ZonedDateTime lastInvokedAt;
}
