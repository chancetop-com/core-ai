package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectReportDraft;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Resets ONE subject's analysis data back to a clean slate (the page-level equivalent of the
 * local reset script): deletes the subject's events/attributions/drafts, clears its analysis and
 * report state and strips its rows from the project's embedded arrays. Project-level state
 * (cursors, claims, other subjects) is NOT touched: the attribution stage's fresh pass has no
 * lower bound, so the subject's material gets re-attributed over the next few runs by itself.
 * The cost snapshot is recomputed right away so only this subject's numbers drop.
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
    ProjectStatsQueryService statsQueryService;

    public void reset(String projectId, String subjectId) {
        var project = projectCollection.get(projectId)
            .orElseThrow(() -> new NotFoundException("project not found, id=" + projectId));
        var subject = subjectCollection.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) {
            throw new BadRequestException("subject does not belong to the project, subjectId=" + subjectId);
        }
        eventCollection.delete(Filters.eq("subject_id", subjectId));
        attributionCollection.delete(Filters.eq("subject_id", subjectId));
        draftCollection.delete(Filters.eq("subject_id", subjectId));
        subjectCollection.update(Filters.eq("_id", subjectId), Updates.combine(
            Updates.set("status", null),
            Updates.set("updated_at", ZonedDateTime.now()),
            Updates.unset("analyzed_at"),
            Updates.unset("profile"),
            Updates.unset("report_file_id"),
            Updates.unset("report_share_token"),
            Updates.unset("report_generated_at"),
            Updates.unset("report_error"),
            Updates.unset("report_events_at"),
            Updates.unset("report_run_id"),
            Updates.unset("report_draft_id")));
        stripProjectRows(project, subjectId);
        statsQueryService.refresh(projectId);
    }

    private void stripProjectRows(Project project, String subjectId) {
        // replace() instead of $set: core-ng has no codec for the embedded row classes
        // (ProjectKpiRecord etc.), so Updates.set with entity lists fails with "Can't find a codec"
        project.kpis = stripKpis(project.kpis, subjectId);
        project.actionItems = stripActions(project.actionItems, subjectId);
        project.notes = stripNotes(project.notes, subjectId);
        var statuses = new ArrayList<ai.core.server.domain.ProjectSubjectStatus>();
        if (project.subjectStatuses != null) {
            for (var status : project.subjectStatuses) {
                if (!subjectId.equals(status.subjectId)) statuses.add(status);
            }
        }
        project.subjectStatuses = statuses;
        project.updatedAt = ZonedDateTime.now();
        projectCollection.replace(project);
    }

    private List<ai.core.server.domain.ProjectKpiRecord> stripKpis(List<ai.core.server.domain.ProjectKpiRecord> rows, String subjectId) {
        if (rows == null) return List.of();
        return rows.stream().filter(r -> !subjectId.equals(r.subjectId)).toList();
    }

    private List<ai.core.server.domain.ProjectActionItem> stripActions(List<ai.core.server.domain.ProjectActionItem> rows, String subjectId) {
        if (rows == null) return List.of();
        return rows.stream().filter(r -> !subjectId.equals(r.subjectId)).toList();
    }

    private List<ai.core.server.domain.ProjectNote> stripNotes(List<ai.core.server.domain.ProjectNote> rows, String subjectId) {
        if (rows == null) return List.of();
        return rows.stream().filter(r -> !subjectId.equals(r.subjectId)).toList();
    }
}
