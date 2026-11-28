package ai.core.server.skill.telemetry;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * PostgreSQL row mapping for app.skill_runtime_stats.
 *
 * @author stephen
 */
public class PgSkillRuntimeStatRow {
    @Column(name = "skill_id")
    public String skillId;

    @Column(name = "package_digest")
    public String packageDigest;

    @Column(name = "invoke_count")
    public Long invokeCount;

    @Column(name = "resource_read_count")
    public Long resourceReadCount;

    @Column(name = "materialize_fail_count")
    public Long materializeFailCount;

    @Column(name = "attributed_script_calls")
    public Long attributedScriptCalls;

    @Column(name = "attributed_script_errors")
    public Long attributedScriptErrors;

    @Column(name = "last_invoked_at")
    public ZonedDateTime lastInvokedAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
