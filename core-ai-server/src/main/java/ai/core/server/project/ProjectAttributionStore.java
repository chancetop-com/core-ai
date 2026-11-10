package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Low-level read/write surface of the attribution table. Bound early (ProjectAttributionModule) because
 * the artifact sinks, the run creator and the upload controller all write attributions long before
 * ProjectModule loads; it depends on Mongo collections only.
 * <p>
 * Enforces the one-home rule for files: within a project a file is attributed to at most one subject.
 * Sessions and runs keep the historical multi-subject semantics. File rows carry the file's created_at and
 * producing agent (denormalized), so the report directory pages on this table alone.
 *
 * @author stephen
 */
public class ProjectAttributionStore {
    public static final String TARGET_SESSION = "session";
    public static final String TARGET_RUN = "run";
    public static final String TARGET_WORKFLOW_RUN = "workflow_run";
    public static final String TARGET_FILE = "file";
    public static final List<String> TARGET_TYPES = List.of(TARGET_SESSION, TARGET_RUN, TARGET_WORKFLOW_RUN, TARGET_FILE);

    public static final String SOURCE_ATTRIBUTOR = "attributor";
    public static final String SOURCE_SCHEDULE = "schedule";
    public static final String SOURCE_INHERITED = "inherited";
    public static final String SOURCE_CASCADE = "cascade";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_UPLOAD = "upload";

    @Inject
    ProjectAttributionRepository attributionRepository;
    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    ai.core.server.file.FileRecordStore fileRecordStore;

    public Optional<ProjectSubject> subject(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) return Optional.empty();
        return subjectStore.get(subjectId);
    }

    /** every attribution row of one target, across projects */
    public List<ProjectSubjectAttribution> byTarget(String targetType, String targetId) {
        return attributionRepository.byTarget(targetType, targetId);
    }

    /** file id → subject id for one project (unique per file by index); ids only, no payload */
    public Map<String, String> fileSubjects(String projectId) {
        return attributionRepository.fileSubjects(projectId);
    }

    public Optional<ProjectSubjectAttribution> fileAttribution(String projectId, String fileId) {
        return attributionRepository.fileAttribution(projectId, fileId);
    }

    /**
     * One page of filed reports: file rows of the project (optionally one subject) within the time window,
     * newest file first. Index-served on (project_id|subject_id, target_type, target_created_at).
     */
    public List<ProjectSubjectAttribution> filedFiles(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to, int offset, int limit) {
        return attributionRepository.filedFiles(projectId, subjectId, from, to, offset, limit);
    }

    public long filedFileCount(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to) {
        return attributionRepository.filedFileCount(projectId, subjectId, from, to);
    }

    /** per-subject report count and newest report time for one project (group on the attribution table) */
    public Map<String, SubjectFileStats> fileStatsBySubject(String projectId) {
        return attributionRepository.fileStatsBySubject(projectId);
    }

    /**
     * Inserts the attribution unless it already exists. For files the check is project-wide: a file already
     * attributed to ANOTHER subject of the same project is a CONFLICT and nothing is written (callers that
     * want to override use {@link #moveFile}). File rows get created_at/agent metadata via {@link #attributeFile}.
     */
    public Result attribute(String projectId, String subjectId, String targetType, String targetId, String source) {
        if (TARGET_FILE.equals(targetType)) return attributeFile(projectId, subjectId, targetId, source, null, null);
        if (attributionRepository.exists(subjectId, targetType, targetId)) return Result.EXISTS;
        return attributionRepository.insertIfAbsent(row(projectId, subjectId, targetType, targetId, source))
            ? Result.INSERTED : Result.EXISTS;
    }

    /**
     * File attribution with the denormalized file metadata. createdAt/agentId may be null: created_at is then
     * read from file_records (one indexed lookup), agent stays unknown (upload / legacy).
     */
    public Result attributeFile(String projectId, String subjectId, String fileId, String source, String agentId, ZonedDateTime createdAt) {
        var existing = fileAttribution(projectId, fileId);
        if (existing.isPresent()) return subjectId.equals(existing.get().subjectId) ? Result.EXISTS : Result.CONFLICT;
        var attribution = fileRow(projectId, subjectId, fileId, source, agentId, createdAt);
        if (attribution == null) return Result.MISSING;
        if (attributionRepository.insertIfAbsent(attribution)) return Result.INSERTED;
        // concurrent writer won the unique index race; report what is there now
        var current = fileAttribution(projectId, fileId);
        return current.isPresent() && !subjectId.equals(current.get().subjectId) ? Result.CONFLICT : Result.EXISTS;
    }

    /** Re-homes a file inside a project: drops its current rows, inserts the new one (null subject = unassigned). */
    public void moveFile(String projectId, String subjectId, String fileId, String source) {
        var previous = fileAttribution(projectId, fileId).orElse(null);
        attributionRepository.deleteByProjectAndTarget(projectId, TARGET_FILE, fileId);
        if (subjectId == null || subjectId.isBlank()) return;
        // keep the file metadata of the previous home so the moved report keeps its date and producer
        var attribution = fileRow(projectId, subjectId, fileId, source,
            previous != null ? previous.agentId : null, previous != null ? previous.targetCreatedAt : null);
        if (attribution == null) return;   // file gone: nothing left to re-home
        attributionRepository.insertIfAbsent(attribution);
    }

    /** the file was deleted: drop its rows in every project (called by FileService.delete) */
    public void removeFile(String fileId) {
        attributionRepository.deleteByTargetTypeAndId(TARGET_FILE, fileId);
    }

    // null when the file does not exist: no attribution row is written for a file nobody can open
    private ProjectSubjectAttribution fileRow(String projectId, String subjectId, String fileId, String source, String agentId, ZonedDateTime createdAt) {
        var at = createdAt != null ? createdAt : fileCreatedAt(fileId);
        if (at == null) return null;
        var attribution = row(projectId, subjectId, TARGET_FILE, fileId, source);
        attribution.agentId = agentId;
        attribution.targetCreatedAt = at;
        return attribution;
    }

    private ZonedDateTime fileCreatedAt(String fileId) {
        var record = fileRecordStore.get(fileId).orElse(null);
        if (record == null) return null;
        return record.createdAt != null ? record.createdAt : ZonedDateTime.now();
    }

    private ProjectSubjectAttribution row(String projectId, String subjectId, String targetType, String targetId, String source) {
        var attribution = new ProjectSubjectAttribution();
        attribution.id = UUID.randomUUID().toString();
        attribution.projectId = projectId;
        attribution.subjectId = subjectId;
        attribution.targetType = targetType;
        attribution.targetId = targetId;
        attribution.source = source;
        attribution.createdAt = ZonedDateTime.now();
        return attribution;
    }

    public enum Result {
        INSERTED, EXISTS, CONFLICT,
        MISSING   // file record does not exist: nothing written
    }

    public record SubjectFileStats(long count, ZonedDateTime latest) {
    }
}
