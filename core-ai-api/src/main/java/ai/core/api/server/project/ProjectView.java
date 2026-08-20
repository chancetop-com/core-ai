package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Cockpit data for one project. The project itself is a scaffold (no state of its own);
 * subject_statuses/kpis/action_items/notes are subject-scoped, filtered to the request's subject.
 *
 * @author stephen
 */
public class ProjectView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "goal")
    public String goal;

    @Property(name = "playbook")
    public String playbook;

    @Property(name = "report_sources")
    public List<ProjectReportSourceView> reportSources;

    @Property(name = "status")
    public String status;

    @Property(name = "last_analyzed_at")
    public ZonedDateTime lastAnalyzedAt;

    @Property(name = "attribution_backfilled_at")
    public ZonedDateTime attributionBackfilledAt;   // backfill watermark; epoch (year 2000) = all history attributed

    @Property(name = "last_analysis_at")
    public ZonedDateTime lastAnalysisAt;

    @Property(name = "analysis_status")
    public String analysisStatus;

    @Property(name = "analysis_error")
    public String analysisError;

    @Property(name = "subjects")
    public List<ProjectSubjectView> subjects;

    @Property(name = "subject_statuses")
    public List<ProjectSubjectStatusView> subjectStatuses;

    @Property(name = "kpis")
    public List<ProjectKpiView> kpis;

    @Property(name = "action_items")
    public List<ProjectActionItemView> actionItems;

    @Property(name = "notes")
    public List<ProjectNoteView> notes;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Property(name = "archived_at")
    public ZonedDateTime archivedAt;
}
