package ai.core.server.asynctask;

import ai.core.persistence.PersistenceProvider;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Mongo-backed store for the async task manager. Unlike the file/redis providers it can enumerate
 * keys, which is what lets the server drive open tasks without an agent asking.
 *
 * @author stephen
 */
public class MongoAsyncTaskPersistence implements PersistenceProvider {
    @Inject
    MongoCollection<AsyncToolTaskRecord> collection;

    @Override
    public void save(String id, String context) {
        var record = new AsyncToolTaskRecord();
        record.id = id;
        record.data = context;
        record.updatedAt = ZonedDateTime.now();
        collection.replace(record);
    }

    @Override
    public void clear() {
        collection.delete(Filters.empty());
    }

    @Override
    public void delete(List<String> ids) {
        if (ids.isEmpty()) return;
        collection.delete(Filters.in("_id", ids));
    }

    @Override
    public Optional<String> load(String id) {
        return collection.get(id).map(record -> record.data);
    }

    @Override
    public List<String> listIds(String prefix) {
        return collection.find(Filters.regex("_id", Pattern.compile("^" + Pattern.quote(prefix)))).stream()
            .map(record -> record.id).toList();
    }
}
