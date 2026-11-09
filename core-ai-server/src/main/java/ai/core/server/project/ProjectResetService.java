package ai.core.server.project;

import core.framework.inject.Inject;
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
    ProjectStore projectStore;
    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    ProjectAttributionRepository attributionRepository;
    @Inject
    ProjectEventStore eventStore;
    @Inject
    ProjectReportDraftStore draftStore;
    @Inject
    ProjectTargetScanRepository scanRepository;
    @Inject
    ProjectStatsQueryService statsQueryService;

    public void reset(String projectId, String subjectId) {
        projectStore.get(projectId)
            .orElseThrow(() -> new NotFoundException("project not found, id=" + projectId));
        var subject = subjectStore.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) {
            throw new BadRequestException("subject does not belong to the project, subjectId=" + subjectId);
        }
        dropScans(projectId, subjectId);
        eventStore.deleteBySubject(subjectId);
        attributionRepository.deleteBySubject(subjectId);
        draftStore.deleteBySubject(subjectId);
        subjectStore.update(subjectId, entity -> {
            entity.status = null;
            entity.updatedAt = ZonedDateTime.now();
            entity.analyzedAt = null;
            entity.profile = null;
            entity.phase = null;
            entity.summary = null;
            entity.statusUpdatedAt = null;
            entity.statusUpdatedBy = null;
            entity.actionItems = null;
            entity.reportFileId = null;
            entity.reportShareToken = null;
            entity.reportGeneratedAt = null;
            entity.reportError = null;
            entity.reportEventsAt = null;
            entity.reportRunId = null;
            entity.reportDraftId = null;
        });
        statsQueryService.refresh(projectId);
    }

    // the subject's attributed session/run/workflow-run targets lose their "offered" marker
    private void dropScans(String projectId, String subjectId) {
        for (var row : attributionRepository.listBySubject(subjectId)) {
            if (ProjectAttributionStore.TARGET_FILE.equals(row.targetType)) continue;
            scanRepository.deleteByProjectAndTarget(projectId, row.targetType, row.targetId);
        }
    }
}
