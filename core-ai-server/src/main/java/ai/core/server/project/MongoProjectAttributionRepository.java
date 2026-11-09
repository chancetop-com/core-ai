package ai.core.server.project;

import ai.core.server.domain.ProjectSubjectAttribution;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class MongoProjectAttributionRepository implements ProjectAttributionRepository {
    private static final int DUPLICATE_KEY_CODE = 11000;
    private static final ZonedDateTime EPOCH = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    public static MongoProjectAttributionRepository of(MongoCollection<ProjectSubjectAttribution> collection) {
        var repository = new MongoProjectAttributionRepository();
        repository.collection = collection;
        return repository;
    }

    @Inject
    MongoCollection<ProjectSubjectAttribution> collection;

    @Override
    public Optional<ProjectSubjectAttribution> get(String id) {
        return collection.get(id);
    }

    @Override
    public List<ProjectSubjectAttribution> byTarget(String targetType, String targetId) {
        return collection.find(Filters.and(Filters.eq("target_type", targetType), Filters.eq("target_id", targetId)));
    }

    @Override
    public Map<String, String> fileSubjects(String projectId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", ProjectAttributionStore.TARGET_FILE));
        query.projection = Projections.include("target_id", "subject_id");
        var result = new HashMap<String, String>();
        for (var row : collection.find(query)) result.putIfAbsent(row.targetId, row.subjectId);
        return result;
    }

    @Override
    public Optional<ProjectSubjectAttribution> fileAttribution(String projectId, String fileId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", ProjectAttributionStore.TARGET_FILE), Filters.eq("target_id", fileId));
        query.limit = 1;
        return collection.find(query).stream().findFirst();
    }

    @Override
    public List<ProjectSubjectAttribution> filedFiles(String projectId, String subjectId,
                                                       ZonedDateTime from, ZonedDateTime to, int offset, int limit) {
        var query = new Query();
        query.filter = filedFilter(projectId, subjectId, from, to);
        query.sort = Sorts.descending("target_created_at");
        query.skip = offset;
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public long filedFileCount(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to) {
        return collection.count(filedFilter(projectId, subjectId, from, to));
    }

    @Override
    public Map<String, ProjectAttributionStore.SubjectFileStats> fileStatsBySubject(String projectId) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.eq("project_id", projectId),
                Filters.eq("target_type", ProjectAttributionStore.TARGET_FILE))),
            Aggregates.group("$subject_id", Accumulators.sum("count", 1), Accumulators.max("latest", "$target_created_at")));
        var result = new HashMap<String, ProjectAttributionStore.SubjectFileStats>();
        for (var doc : collection.aggregate(aggregate)) {
            var subjectId = doc.getString("_id");
            if (subjectId == null) continue;
            var latest = doc.get("latest") instanceof Date date ? date.toInstant().atZone(ZoneOffset.UTC) : null;
            result.put(subjectId, new ProjectAttributionStore.SubjectFileStats(((Number) doc.get("count")).longValue(), latest));
        }
        return result;
    }

    @Override
    public boolean exists(String subjectId, String targetType, String targetId) {
        return collection.count(Filters.and(Filters.eq("subject_id", subjectId),
            Filters.eq("target_type", targetType), Filters.eq("target_id", targetId))) > 0;
    }

    @Override
    public boolean insertIfAbsent(ProjectSubjectAttribution attribution) {
        try {
            collection.insert(attribution);
            return true;
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            return false;
        }
    }

    @Override
    public void update(String id, Consumer<ProjectSubjectAttribution> mutator) {
        collection.get(id).ifPresent(attribution -> {
            mutator.accept(attribution);
            collection.replace(attribution);
        });
    }

    @Override
    public void deleteById(String id) {
        collection.delete(id);
    }

    @Override
    public void deleteBySubject(String subjectId) {
        collection.delete(Filters.eq("subject_id", subjectId));
    }

    @Override
    public void deleteByProjectAndTarget(String projectId, String targetType, String targetId) {
        collection.delete(Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", targetType), Filters.eq("target_id", targetId)));
    }

    @Override
    public void clearAnalyzed(String projectId, String targetType, String targetId) {
        collection.update(Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", targetType), Filters.eq("target_id", targetId)), Updates.unset("analyzed_at"));
    }

    @Override
    public void deleteByTargetTypeAndId(String targetType, String targetId) {
        collection.delete(Filters.and(Filters.eq("target_type", targetType), Filters.eq("target_id", targetId)));
    }

    @Override
    public List<ProjectSubjectAttribution> listBySubject(String subjectId) {
        return collection.find(Filters.eq("subject_id", subjectId));
    }

    @Override
    public long countBySubject(String subjectId) {
        return collection.count(Filters.eq("subject_id", subjectId));
    }

    @Override
    public List<ProjectSubjectAttribution> listBySubjectAndType(String subjectId, String targetType) {
        return collection.find(Filters.and(Filters.eq("subject_id", subjectId), Filters.eq("target_type", targetType)));
    }

    @Override
    public List<ProjectSubjectAttribution> listBySubjectsAndType(Collection<String> subjectIds, String targetType) {
        if (subjectIds.isEmpty()) return List.of();
        return collection.find(Filters.and(Filters.in("subject_id", subjectIds), Filters.eq("target_type", targetType)));
    }

    @Override
    public List<ProjectSubjectAttribution> listByProjectAndType(String projectId, String targetType) {
        return collection.find(Filters.and(Filters.eq("project_id", projectId), Filters.eq("target_type", targetType)));
    }

    @Override
    public List<ProjectSubjectAttribution> listUnanalyzed(String subjectId, int limit) {
        var query = new Query();
        query.filter = unanalyzedFilter(subjectId);
        query.limit = limit;
        return collection.find(query);
    }

    @Override
    public long countUnanalyzed(String subjectId) {
        return collection.count(unanalyzedFilter(subjectId));
    }

    @Override
    public List<ProjectSubjectAttribution> listByProjectAndTargets(String projectId, String targetType, Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        return collection.find(Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", targetType), Filters.in("target_id", targetIds)));
    }

    @Override
    public long countByProjectAndTarget(String projectId, String targetType, String targetId) {
        return collection.count(Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", targetType), Filters.eq("target_id", targetId)));
    }

    private org.bson.conversions.Bson unanalyzedFilter(String subjectId) {
        return Filters.and(Filters.eq("subject_id", subjectId),
            Filters.or(Filters.exists("analyzed_at", false), Filters.eq("analyzed_at", null)));
    }

    private org.bson.conversions.Bson filedFilter(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("project_id", projectId));
        if (subjectId != null && !subjectId.isBlank()) filters.add(Filters.eq("subject_id", subjectId));
        filters.add(Filters.eq("target_type", ProjectAttributionStore.TARGET_FILE));
        filters.add(Filters.gte("target_created_at", from != null ? from : EPOCH));
        if (to != null) filters.add(Filters.lte("target_created_at", to));
        return Filters.and(filters);
    }
}
