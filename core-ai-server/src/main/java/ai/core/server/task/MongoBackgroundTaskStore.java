package ai.core.server.task;

import ai.core.server.domain.BackgroundTask;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoBackgroundTaskStore implements BackgroundTaskStore {
    public static MongoBackgroundTaskStore of(MongoCollection<BackgroundTask> collection) {
        var store = new MongoBackgroundTaskStore();
        store.taskCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<BackgroundTask> taskCollection;

    @Override
    public Optional<BackgroundTask> get(String id) {
        return taskCollection.get(id);
    }

    @Override
    public List<BackgroundTask> list(String type, int limit) {
        var query = new Query();
        if (type != null && !type.isBlank()) query.filter = Filters.eq("type", type);
        query.sort = Sorts.descending("_id");
        query.limit = Math.min(limit, 100);
        return taskCollection.find(query);
    }

    @Override
    public boolean insertIfAbsent(BackgroundTask task) {
        try {
            taskCollection.insert(task);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public boolean retryClaim(String id, String claimedBy, ZonedDateTime startedAt) {
        return taskCollection.update(
            Filters.and(Filters.eq("_id", id), Filters.eq("status", TaskStatus.FAILED)),
            Updates.combine(
                Updates.set("status", TaskStatus.RUNNING),
                Updates.set("claimed_by", claimedBy),
                Updates.set("started_at", startedAt),
                Updates.inc("retry_count", 1))) > 0;
    }

    @Override
    public void replace(BackgroundTask task) {
        taskCollection.replace(task);
    }
}
