package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.HashMap;
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
 * Sessions and runs keep the historical multi-subject semantics.
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

    private static final int DUPLICATE_KEY_CODE = 11000;

    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;

    public Optional<ProjectSubject> subject(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) return Optional.empty();
        return subjectCollection.get(subjectId);
    }

    /** every attribution row of one target, across projects */
    public List<ProjectSubjectAttribution> byTarget(String targetType, String targetId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("target_type", targetType), Filters.eq("target_id", targetId));
        return attributionCollection.find(query);
    }

    /** file id → subject id for one project (unique per file by index) */
    public Map<String, String> fileSubjects(String projectId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", projectId), Filters.eq("target_type", TARGET_FILE));
        var result = new HashMap<String, String>();
        for (var row : attributionCollection.find(query)) result.putIfAbsent(row.targetId, row.subjectId);
        return result;
    }

    public Optional<ProjectSubjectAttribution> fileAttribution(String projectId, String fileId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", projectId), Filters.eq("target_type", TARGET_FILE), Filters.eq("target_id", fileId));
        query.limit = 1;
        return attributionCollection.find(query).stream().findFirst();
    }

    /**
     * Inserts the attribution unless it already exists. For files the check is project-wide: a file already
     * attributed to ANOTHER subject of the same project is a CONFLICT and nothing is written (callers that
     * want to override use {@link #moveFile}).
     */
    public Result attribute(String projectId, String subjectId, String targetType, String targetId, String source) {
        if (TARGET_FILE.equals(targetType)) {
            var existing = fileAttribution(projectId, targetId);
            if (existing.isPresent()) return subjectId.equals(existing.get().subjectId) ? Result.EXISTS : Result.CONFLICT;
        } else if (attributionCollection.count(Filters.and(
            Filters.eq("subject_id", subjectId), Filters.eq("target_type", targetType), Filters.eq("target_id", targetId))) > 0) {
            return Result.EXISTS;
        }
        try {
            attributionCollection.insert(row(projectId, subjectId, targetType, targetId, source));
            return Result.INSERTED;
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            // concurrent writer won the unique index race; report what is there now
            var current = TARGET_FILE.equals(targetType) ? fileAttribution(projectId, targetId) : Optional.<ProjectSubjectAttribution>empty();
            return current.isPresent() && !subjectId.equals(current.get().subjectId) ? Result.CONFLICT : Result.EXISTS;
        }
    }

    /** Re-homes a file inside a project: drops its current rows, inserts the new one (null subject = unassigned). */
    public void moveFile(String projectId, String subjectId, String fileId, String source) {
        attributionCollection.delete(Filters.and(Filters.eq("project_id", projectId), Filters.eq("target_type", TARGET_FILE), Filters.eq("target_id", fileId)));
        if (subjectId == null || subjectId.isBlank()) return;
        try {
            attributionCollection.insert(row(projectId, subjectId, TARGET_FILE, fileId, source));
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
        }
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
        INSERTED, EXISTS, CONFLICT
    }
}
