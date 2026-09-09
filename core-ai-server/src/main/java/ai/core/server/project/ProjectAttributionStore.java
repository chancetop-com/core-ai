package ai.core.server.project;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
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

    private static final int DUPLICATE_KEY_CODE = 11000;
    private static final Bson FILE_TIME_PROJECTION = Projections.include("_id", "created_at");

    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<FileRecord> fileRecordCollection;

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

    /** file id → subject id for one project (unique per file by index); ids only, no payload */
    public Map<String, String> fileSubjects(String projectId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", projectId), Filters.eq("target_type", TARGET_FILE));
        query.projection = Projections.include("target_id", "subject_id");
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
     * One page of filed reports: file rows of the project (optionally one subject) within the time window,
     * newest file first. Index-served on (project_id|subject_id, target_type, target_created_at).
     */
    public List<ProjectSubjectAttribution> filedFiles(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to, int offset, int limit) {
        var query = new Query();
        query.filter = filedFilter(projectId, subjectId, from, to);
        query.sort = Sorts.descending("target_created_at");
        query.skip = offset;
        query.limit = limit;
        return attributionCollection.find(query);
    }

    public long filedFileCount(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to) {
        return attributionCollection.count(filedFilter(projectId, subjectId, from, to));
    }

    /** per-subject report count and newest report time for one project (group on the attribution table) */
    public Map<String, SubjectFileStats> fileStatsBySubject(String projectId) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.eq("project_id", projectId), Filters.eq("target_type", TARGET_FILE))),
            Aggregates.group("$subject_id", Accumulators.sum("count", 1), Accumulators.max("latest", "$target_created_at")));
        var result = new HashMap<String, SubjectFileStats>();
        for (var doc : attributionCollection.aggregate(aggregate)) {
            var subjectId = doc.getString("_id");
            if (subjectId == null) continue;
            var latest = doc.get("latest") instanceof Date date ? date.toInstant().atZone(ZoneOffset.UTC) : null;
            result.put(subjectId, new SubjectFileStats(((Number) doc.get("count")).longValue(), latest));
        }
        return result;
    }

    /**
     * Inserts the attribution unless it already exists. For files the check is project-wide: a file already
     * attributed to ANOTHER subject of the same project is a CONFLICT and nothing is written (callers that
     * want to override use {@link #moveFile}). File rows get created_at/agent metadata via {@link #attributeFile}.
     */
    public Result attribute(String projectId, String subjectId, String targetType, String targetId, String source) {
        if (TARGET_FILE.equals(targetType)) return attributeFile(projectId, subjectId, targetId, source, null, null);
        if (attributionCollection.count(Filters.and(
            Filters.eq("subject_id", subjectId), Filters.eq("target_type", targetType), Filters.eq("target_id", targetId))) > 0) {
            return Result.EXISTS;
        }
        try {
            attributionCollection.insert(row(projectId, subjectId, targetType, targetId, source));
            return Result.INSERTED;
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            return Result.EXISTS;   // concurrent writer inserted the equivalent row
        }
    }

    /**
     * File attribution with the denormalized file metadata. createdAt/agentId may be null: created_at is then
     * read from file_records (one indexed lookup), agent stays unknown (upload / legacy).
     */
    public Result attributeFile(String projectId, String subjectId, String fileId, String source, String agentId, ZonedDateTime createdAt) {
        var existing = fileAttribution(projectId, fileId);
        if (existing.isPresent()) return subjectId.equals(existing.get().subjectId) ? Result.EXISTS : Result.CONFLICT;
        try {
            attributionCollection.insert(fileRow(projectId, subjectId, fileId, source, agentId, createdAt));
            return Result.INSERTED;
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            // concurrent writer won the unique index race; report what is there now
            var current = fileAttribution(projectId, fileId);
            return current.isPresent() && !subjectId.equals(current.get().subjectId) ? Result.CONFLICT : Result.EXISTS;
        }
    }

    /** Re-homes a file inside a project: drops its current rows, inserts the new one (null subject = unassigned). */
    public void moveFile(String projectId, String subjectId, String fileId, String source) {
        var previous = fileAttribution(projectId, fileId).orElse(null);
        attributionCollection.delete(Filters.and(Filters.eq("project_id", projectId), Filters.eq("target_type", TARGET_FILE), Filters.eq("target_id", fileId)));
        if (subjectId == null || subjectId.isBlank()) return;
        try {
            // keep the file metadata of the previous home so the moved report keeps its date and producer
            attributionCollection.insert(fileRow(projectId, subjectId, fileId, source,
                previous != null ? previous.agentId : null, previous != null ? previous.targetCreatedAt : null));
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
        }
    }

    private Bson filedFilter(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("project_id", projectId));
        if (subjectId != null && !subjectId.isBlank()) filters.add(Filters.eq("subject_id", subjectId));
        filters.add(Filters.eq("target_type", TARGET_FILE));
        if (from != null) filters.add(Filters.gte("target_created_at", from));
        if (to != null) filters.add(Filters.lte("target_created_at", to));
        return Filters.and(filters);
    }

    private ProjectSubjectAttribution fileRow(String projectId, String subjectId, String fileId, String source, String agentId, ZonedDateTime createdAt) {
        var attribution = row(projectId, subjectId, TARGET_FILE, fileId, source);
        attribution.agentId = agentId;
        attribution.targetCreatedAt = createdAt != null ? createdAt : fileCreatedAt(fileId);
        return attribution;
    }

    private ZonedDateTime fileCreatedAt(String fileId) {
        var query = new Query();
        query.filter = Filters.eq("_id", fileId);
        query.projection = FILE_TIME_PROJECTION;
        query.limit = 1;
        var record = fileRecordCollection.find(query).stream().findFirst().orElse(null);
        // unknown file (deleted, or an id the attributor made up): stamp now so the row still sorts
        return record != null && record.createdAt != null ? record.createdAt : ZonedDateTime.now();
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

    public record SubjectFileStats(long count, ZonedDateTime latest) {
    }
}
