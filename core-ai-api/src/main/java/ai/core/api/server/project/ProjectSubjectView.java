package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ProjectSubjectView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "external_link")
    public String externalLink;

    @Property(name = "status")
    public String status;   // started after the first manual analysis; null/not_started = never analyzed

    @Property(name = "attributed_count")
    public Long attributedCount;

    @Property(name = "profile")
    public String profile;   // JSON object text of stable subject facts, extracted by the analyzer

    @Property(name = "analyzed_at")
    public ZonedDateTime analyzedAt;

    // ---- HTML campaign report (v1.4): subject-level — KPI series only make sense within one subject
    @Property(name = "report_file_id")
    public String reportFileId;

    @Property(name = "report_share_token")
    public String reportShareToken;   // iframe src = /api/public/artifacts/{token}/content

    @Property(name = "report_generated_at")
    public ZonedDateTime reportGeneratedAt;

    @Property(name = "report_error")
    public String reportError;

    @Property(name = "report_run_id")
    public String reportRunId;   // non-null = a render is in flight (completion job assembles it)

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
