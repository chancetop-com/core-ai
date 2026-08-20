package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Lightweight campaign container: loose organization of agents/workflows/sessions/traces/artifacts.
 * The project itself is a scaffold — it defines the campaign (playbook, report sources) but holds
 * NO state of its own: status/kpis/action items/notes all belong to {@link ProjectSubject}s.
 *
 * @author stephen
 */
@Collection(name = "projects")
public class Project {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;   // owner

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @Field(name = "goal")
    public String goal;

    // free-text campaign definition: overall process + KPI evaluation methodology (human-written)
    @Field(name = "playbook")
    public String playbook;

    // which members produce the reports this project evaluates (structured, machine-readable)
    @Field(name = "report_sources")
    public List<ProjectReportSource> reportSources;

    // members attached to this project (membership lives on the project side — definitions carry no project link)
    @Field(name = "members")
    public List<ProjectMemberRef> members;

    // active | archived; validated in service layer
    @NotNull
    @Field(name = "status")
    public String status;

    // ---- project agent analysis cursor / single-flight state ----
    @Field(name = "last_analyzed_at")
    public ZonedDateTime lastAnalyzedAt;   // incremental cursor: material newer than this is analyzed next run

    // backfill watermark: records OLDER than this have been scanned for attribution, batch by
    // batch. Each successful attribution run pushes it further into the past until all legacy
    // material is covered (epoch = backfill complete). Exposed in the UI as "attribution processed
    // up to <date>" so incremental coverage is visible.
    @Field(name = "attribution_backfilled_at")
    public ZonedDateTime attributionBackfilledAt;

    @Field(name = "analysis_status")
    public String analysisStatus;          // running | error (single-flight claim marker; null = idle)

    @Field(name = "analysis_error")
    public String analysisError;           // last failed analysis message

    @Field(name = "analysis_run_id")
    public String analysisRunId;           // the main project-agent run id, polled to detect completion

    @Field(name = "analysis_claimed_at")
    public ZonedDateTime analysisClaimedAt;   // when the running claim was taken; used to detect stale claims

    @Field(name = "last_analysis_at")
    public ZonedDateTime lastAnalysisAt;      // when the subject-analysis stage last completed (display: next ≈ +1h)

    // v1.4 event backfill marker (migration idempotency only, not a report watermark — the report
    // watermark lives on the subject)
    @Field(name = "events_backfilled_at")
    public ZonedDateTime eventsBackfilledAt;

    @Field(name = "subject_statuses")
    public List<ProjectSubjectStatus> subjectStatuses;

    // cached cost snapshot lives in the project_stats collection (separate entity — core-ng
    // generates codecs only for registered @Collection classes, so $set on an embedded instance
    // fails); stats_dirty/last_stats_at stay here for the refresh job scan and display
    @Field(name = "stats_dirty")
    public Boolean statsDirty;

    @Field(name = "last_stats_at")
    public ZonedDateTime lastStatsAt;

    @Field(name = "kpis")
    public List<ProjectKpiRecord> kpis;

    @Field(name = "action_items")
    public List<ProjectActionItem> actionItems;

    @Field(name = "notes")
    public List<ProjectNote> notes;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "archived_at")
    public ZonedDateTime archivedAt;
}
