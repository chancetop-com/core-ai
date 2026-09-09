package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Lightweight campaign container: loose organization of agents/workflows/sessions/traces/artifacts.
 * The project itself is a scaffold — it defines the campaign (playbook, report sources, members)
 * and carries the analysis cursors; it holds NO subject state. Current state (phase/summary/action
 * items) lives on {@link ProjectSubject}, history (KPIs/notes/transitions) in {@link ProjectSubjectEvent}.
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

    // ---- analysis pipeline cursors / single-flight state ----
    @Field(name = "last_analyzed_at")
    public ZonedDateTime lastAnalyzedAt;   // when the attribution stage last completed (attribution job gate)

    // forward-scan cursor of the attribution stage: member records with a material time up to this
    // point have been offered to the attributor at least once. Sessions that grow past it are
    // offered again. Exposed in the UI as "attribution scanned through <date>".
    @Field(name = "attribution_backfilled_at")
    public ZonedDateTime attributionBackfilledAt;

    @Field(name = "analysis_status")
    public String analysisStatus;          // running | error | idle (single-flight claim marker; null = idle)

    @Field(name = "analysis_error")
    public String analysisError;           // last failed analysis message

    @Field(name = "analysis_claimed_at")
    public ZonedDateTime analysisClaimedAt;   // heartbeat of the running claim; stale = the process died mid-run

    @Field(name = "last_analysis_at")
    public ZonedDateTime lastAnalysisAt;      // when the subject-analysis stage last completed (analysis job gate)

    // v1.4 event backfill marker (migration idempotency only)
    @Field(name = "events_backfilled_at")
    public ZonedDateTime eventsBackfilledAt;

    // cached cost snapshot lives in the project_stats collection (separate entity — core-ng
    // generates codecs only for registered @Collection classes, so $set on an embedded instance
    // fails); stats_dirty/last_stats_at stay here for the refresh job scan and display
    @Field(name = "stats_dirty")
    public Boolean statsDirty;

    @Field(name = "last_stats_at")
    public ZonedDateTime lastStatsAt;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "archived_at")
    public ZonedDateTime archivedAt;
}
