package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Review surface of auto-discovered subjects: accept, reject or merge a proposal, plus the manual
 * rescan of material that was offered but never produced an attribution. Every operation requires
 * project.manage, and accept/reject/merge only apply to {@code status=proposed} rows so a real
 * subject can never be touched by mistake.
 *
 * @author stephen
 */
public class ProjectSubjectReviewService {
    /** normalized aliases of a subject, used by the attribution stage's dedup match */
    static List<String> aliasesOf(ProjectSubject subject) {
        if (!(profileOf(subject).get("aliases") instanceof List<?> raw)) return List.of();
        var aliases = new ArrayList<String>();
        for (var alias : raw) {
            if (alias == null) continue;
            var normalized = ProjectSubjectNames.normalize(alias.toString());
            if (normalized != null && !normalized.isBlank()) aliases.add(normalized);
        }
        return aliases;
    }

    private static Map<String, Object> profileOf(ProjectSubject subject) {
        if (subject.profile == null || !subject.profile.trim().startsWith("{")) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(JsonUtil.toMap(subject.profile));
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    @Inject
    ProjectStore projectStore;
    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    ProjectAttributionRepository attributionRepository;
    @Inject
    ProjectService projectService;
    @Inject
    ProjectStateService stateService;
    @Inject
    ProjectAttributionStore attributionStore;
    @Inject
    ProjectTargetScanStore scanStore;

    public void acceptSubject(String projectId, String userId, boolean admin, String subjectId) {
        var project = projectService.require(projectId);
        projectService.requireAccess(project, userId, admin);
        requireProposed(projectId, subjectId);
        // recordSubjectStatus writes status + the subject_status event (a proposal has no event yet)
        stateService.recordSubjectStatus(projectId, subjectId, ProjectService.SUBJECT_STARTED, userId);
        subjectStore.update(subjectId, entity -> {
            entity.proposedAt = null;
            entity.updatedAt = ZonedDateTime.now();
        });
    }

    public void rejectSubject(String projectId, String userId, boolean admin, String subjectId) {
        var project = projectService.require(projectId);
        projectService.requireAccess(project, userId, admin);
        var subject = requireProposed(projectId, subjectId);
        attributionRepository.deleteBySubject(subjectId);
        stateService.deleteSubjectEvents(subjectId);
        subjectStore.delete(subjectId);
        // scan markers stay (the material WAS offered); the rejected name list is what keeps the
        // attributor from proposing the same entity again
        var normalized = ProjectSubjectNames.normalize(subject.name);
        projectStore.update(projectId, entity -> {
            if (entity.rejectedSubjectNames == null) entity.rejectedSubjectNames = new ArrayList<>();
            if (!entity.rejectedSubjectNames.contains(normalized)) entity.rejectedSubjectNames.add(normalized);
            entity.statsDirty = Boolean.TRUE;
            entity.updatedAt = ZonedDateTime.now();
        });
    }

    public void mergeSubject(String projectId, String userId, boolean admin, String subjectId, String intoSubjectId) {
        var project = projectService.require(projectId);
        projectService.requireAccess(project, userId, admin);
        var from = requireProposed(projectId, subjectId);
        if (intoSubjectId == null || intoSubjectId.isBlank()) throw new BadRequestException("into_subject_id is required");
        if (subjectId.equals(intoSubjectId)) throw new BadRequestException("cannot merge a subject into itself");
        var into = projectService.subject(projectId, intoSubjectId);
        if (ProjectService.SUBJECT_PROPOSED.equals(into.status)) {
            throw new BadRequestException("merge target is itself a pending proposal; accept it first");
        }
        for (var row : attributionRepository.listBySubject(subjectId)) rewrite(row, projectId, into.id);
        stateService.deleteSubjectEvents(subjectId);
        subjectStore.delete(subjectId);
        addAlias(into, from.name);
        projectStore.update(projectId, entity -> {
            entity.statsDirty = Boolean.TRUE;
            entity.updatedAt = ZonedDateTime.now();
        });
    }

    /**
     * Clears the scan markers of targets that never produced an attribution and rewinds the attribution
     * cursor to the oldest marker, so the rescan reaches the whole scanned range and not just the part
     * the cursor happens to sit on. Explicit and costly: the material is offered to the LLM again.
     */
    public long rescanUnassigned(String projectId, String userId, boolean admin) {
        var project = projectService.require(projectId);
        projectService.requireAccess(project, userId, admin);
        var rescan = scanStore.dropUnattributed(projectId);
        if (rescan.dropped() > 0) {
            // no marker at all means nothing was ever scanned: rewind to the start so the walk restarts
            var oldest = rescan.oldestMarkerAt();
            projectService.rewindAttributionBackfill(projectId, oldest != null ? oldest : ProjectAttributionStage.EPOCH);
        }
        return rescan.dropped();
    }

    // files keep the one-home rule (moveFile drops the old row first); session/run rows are
    // de-duplicated by attribute() and only then removed from the source subject
    private void rewrite(ProjectSubjectAttribution row, String projectId, String intoSubjectId) {
        if (ProjectAttributionStore.TARGET_FILE.equals(row.targetType)) {
            attributionStore.moveFile(projectId, intoSubjectId, row.targetId, ProjectAttributionStore.SOURCE_ATTRIBUTOR);
            return;
        }
        attributionStore.attribute(projectId, intoSubjectId, row.targetType, row.targetId, ProjectAttributionStore.SOURCE_ATTRIBUTOR);
        attributionRepository.deleteById(row.id);
    }

    // profile is extracted ONCE by the analyzer and never overwritten, so an alias written here
    // survives: the next attribution round matches the merged name and attributes to the target
    private void addAlias(ProjectSubject subject, String name) {
        if (name == null || name.isBlank()) return;
        var profile = profileOf(subject);
        var aliases = new ArrayList<String>();
        if (profile.get("aliases") instanceof List<?> existing) {
            for (var alias : existing) {
                if (alias != null) aliases.add(alias.toString());
            }
        }
        if (aliases.stream().anyMatch(name::equalsIgnoreCase)) return;
        aliases.add(name);
        profile.put("aliases", aliases);
        subjectStore.update(subject.id, entity -> {
            entity.profile = JsonUtil.toJson(profile);
            entity.updatedAt = ZonedDateTime.now();
        });
    }

    private ProjectSubject requireProposed(String projectId, String subjectId) {
        var subject = projectService.subject(projectId, subjectId);
        if (!ProjectService.SUBJECT_PROPOSED.equals(subject.status)) {
            throw new BadRequestException("subject is not a pending proposal, subjectId=" + subjectId);
        }
        return subject;
    }
}
