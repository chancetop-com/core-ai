package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Response body for GET /api/skills/:id/telemetry.
 *
 * @author stephen
 */
public class SkillTelemetryResponse {
    @NotNull
    @Property(name = "skill_id")
    public String skillId;

    @NotNull
    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "current_digest")
    public String currentDigest;

    @NotNull
    @Property(name = "telemetry_available")
    public Boolean telemetryAvailable;

    @NotNull
    @Property(name = "total_invoke_count")
    public Long totalInvokeCount;

    @NotNull
    @Property(name = "total_resource_read_count")
    public Long totalResourceReadCount;

    @NotNull
    @Property(name = "total_materialize_fail_count")
    public Long totalMaterializeFailCount;

    @NotNull
    @Property(name = "total_attributed_script_calls")
    public Long totalAttributedScriptCalls;

    @NotNull
    @Property(name = "total_attributed_script_errors")
    public Long totalAttributedScriptErrors;

    @NotNull
    @Property(name = "open_papercut_count")
    public Integer openPapercutCount;

    @Property(name = "last_invoked_at")
    public ZonedDateTime lastInvokedAt;

    @NotNull
    @Property(name = "versions")
    public List<SkillRuntimeStatView> versions;
}
