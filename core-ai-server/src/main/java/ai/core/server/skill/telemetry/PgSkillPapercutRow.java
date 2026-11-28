package ai.core.server.skill.telemetry;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * PostgreSQL row mapping for app.skill_papercuts.
 *
 * @author stephen
 */
public class PgSkillPapercutRow {
    @Column(name = "id")
    public String id;

    @Column(name = "skill_id")
    public String skillId;

    @Column(name = "qualified_name")
    public String qualifiedName;

    @Column(name = "package_digest")
    public String packageDigest;

    @Column(name = "dedup_key")
    public String dedupKey;

    @Column(name = "category")
    public String category;

    @Column(name = "status")
    public String status;

    @Column(name = "summary")
    public String summary;

    @Column(name = "latest_observed_error")
    public String latestObservedError;

    @Column(name = "suggested_fix")
    public String suggestedFix;

    @Column(name = "occurrence_count")
    public Integer occurrenceCount;

    @Column(name = "reporter_user_id")
    public String reporterUserId;

    @Column(name = "latest_session_id")
    public String latestSessionId;

    @Column(name = "resolved_by")
    public String resolvedBy;

    @Column(name = "resolved_at")
    public ZonedDateTime resolvedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "last_seen_at")
    public ZonedDateTime lastSeenAt;
}
