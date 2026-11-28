package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * View DTO for a skill papercut friction record.
 *
 * @author stephen
 */
public class SkillPapercutView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "skill_id")
    public String skillId;

    @NotNull
    @Property(name = "qualified_name")
    public String qualifiedName;

    @NotNull
    @Property(name = "package_digest")
    public String packageDigest;

    @NotNull
    @Property(name = "category")
    public SkillPapercutCategoryView category;

    @NotNull
    @Property(name = "status")
    public SkillPapercutStatusView status;

    @NotNull
    @Property(name = "summary")
    public String summary;

    @Property(name = "latest_observed_error")
    public String latestObservedError;

    @Property(name = "suggested_fix")
    public String suggestedFix;

    @NotNull
    @Property(name = "occurrence_count")
    public Integer occurrenceCount;

    @Property(name = "reporter_user_id")
    public String reporterUserId;

    @Property(name = "latest_session_id")
    public String latestSessionId;

    @Property(name = "resolved_by")
    public String resolvedBy;

    @Property(name = "resolved_at")
    public ZonedDateTime resolvedAt;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "last_seen_at")
    public ZonedDateTime lastSeenAt;
}
