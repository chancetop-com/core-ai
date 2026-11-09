package ai.core.server.project;

import ai.core.server.domain.ProjectTargetScan;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoProjectTargetScanRepository implements ProjectTargetScanRepository {
    private static final int DUPLICATE_KEY_CODE = 11000;

    public static MongoProjectTargetScanRepository of(MongoCollection<ProjectTargetScan> collection) {
        var repository = new MongoProjectTargetScanRepository();
        repository.collection = collection;
        return repository;
    }

    @Inject
    MongoCollection<ProjectTargetScan> collection;

    @Override
    public List<ProjectTargetScan> listByProjectAndTargets(String projectId, String targetType, Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", targetType), Filters.in("target_id", targetIds));
        return collection.find(query);
    }

    @Override
    public List<ProjectTargetScan> listByProject(String projectId) {
        return collection.find(Filters.eq("project_id", projectId));
    }

    @Override
    public Optional<ProjectTargetScan> find(String projectId, String targetType, String targetId) {
        return collection.find(Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", targetType), Filters.eq("target_id", targetId))).stream().findFirst();
    }

    @Override
    public void upsert(ProjectTargetScan scan) {
        var filter = Filters.and(Filters.eq("project_id", scan.projectId),
            Filters.eq("target_type", scan.targetType), Filters.eq("target_id", scan.targetId));
        long updated = collection.update(filter,
            Updates.combine(Updates.set("material_at", scan.materialAt), Updates.set("scanned_at", scan.scannedAt)));
        if (updated > 0) return;
        try {
            collection.insert(scan);
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
        }
    }

    @Override
    public void deleteById(String id) {
        collection.delete(id);
    }

    @Override
    public void deleteByProjectAndTarget(String projectId, String targetType, String targetId) {
        collection.delete(Filters.and(Filters.eq("project_id", projectId),
            Filters.eq("target_type", targetType), Filters.eq("target_id", targetId)));
    }

    @Override
    public Optional<ZonedDateTime> oldestMaterialAt(String projectId) {
        var query = new Query();
        query.filter = Filters.eq("project_id", projectId);
        query.sort = Sorts.ascending("material_at");
        query.limit = 1;
        return collection.find(query).stream().map(scan -> scan.materialAt).findFirst();
    }
}
