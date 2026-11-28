package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * Per-digest runtime telemetry counters for a skill.
 *
 * @author stephen
 */
public class SkillRuntimeStatView {
    @NotNull
    @Property(name = "package_digest")
    public String packageDigest;

    @NotNull
    @Property(name = "invoke_count")
    public Long invokeCount;

    @NotNull
    @Property(name = "resource_read_count")
    public Long resourceReadCount;

    @NotNull
    @Property(name = "materialize_fail_count")
    public Long materializeFailCount;

    @NotNull
    @Property(name = "attributed_script_calls")
    public Long attributedScriptCalls;

    @NotNull
    @Property(name = "attributed_script_errors")
    public Long attributedScriptErrors;

    @Property(name = "last_invoked_at")
    public ZonedDateTime lastInvokedAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
