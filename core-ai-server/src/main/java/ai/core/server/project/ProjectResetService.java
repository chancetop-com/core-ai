package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectReportDraft;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.ProjectTargetScan;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;

/**
 * Resets ONE subject's analysis data back to a clean slate (the page-level equivalent of the
 * local reset script): deletes the subject's events/attributions/drafts and clears its state,
 * analysis and report fields. The scan markers of the material that was attributed to the subject
 * are dropped too, so the attribution stage offers that material again on its next run (the
 * subject gets re-populated by itself). Project-level state (cursors, claims, other subjects) is
 * NOT touched. The cost snapshot is recomputed right away so only this subject's numbers drop.
 *
 * @author stephen
 */
public class ProjectResetService {
    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<ProjectSubjectEvent> eventCollection;
    @Inject
    MongoCollection<ProjectReportDraft> draftCollection;
    @Inject
    MongoCollection<ProjectTargetScan> scanCollection;
    @Inject
    ProjectStatsQueryService statsQueryService;

    public void reset(String projectId, String subjectId) {
        projectCollection.get(projectId)
            .orElseThrow(() -> new NotFoundException("project not found, id=" + projectId));
        var subject = subjectCollection.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) {
            throw new BadRequestException("subject does not belong to the project, subjectId=" + subjectId);
        }
        dropScans(projectId, subjectId);
        eventCollection.delete(Filters.eq("subject_id", subjectId));
        attributionCollection.delete(Filters.eq("subject_id", subjectId));
        draftCollection.delete(Filters.eq("subject_id", subjectId));
        subjectCollection.update(Filters.eq("_id", subjectId), Updates.combine(
            Updates.set("status", null),
            Updates.set("updated_at", ZonedDateTime.now()),
            Updates.unset("analyzed_at"),
            Updates.unset("profile"),
            Updates.unset("phase"),
            Updates.unset("summary"),
            Updates.unset("status_updated_at"),
            Updates.unset("status_updated_by"),
            Updates.unset("action_items"),
            Updates.unset("report_file_id"),
            Updates.unset("report_share_token"),
            Updates.unset("report_generated_at"),
            Updates.unset("report_error"),
            Updates.unset("report_events_at"),
            Updates.unset("report_run_id"),
            Updates.unset("report_draft_id")));
        statsQueryService.refresh(projectId);
    }

    // the subject's attributed session/run/workflow-run targets lose their "offered" marker
    private void dropScans(String projectId, String subjectId) {
        var query = new Query();
        query.filter = Filters.eq("subject_id", subjectId);
        for (var row : attributionCollection.find(query)) {
            if (ProjectAttributionStore.TARGET_FILE.equals(row.targetType)) continue;
            scanCollection.delete(Filters.and(
                Filters.eq("project_id", projectId),
                Filters.eq("target_type", row.targetType),
                Filters.eq("target_id", row.targetId)));
        }
    }
}
