package ai.core.server.runtime;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class MongoRuntimeSampleStore implements RuntimeSampleStore {
    public static MongoRuntimeSampleStore of(MongoCollection<RuntimeSample> collection) {
        var store = new MongoRuntimeSampleStore();
        store.runtimeSampleCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<RuntimeSample> runtimeSampleCollection;

    @Override
    public void insert(RuntimeSample sample) {
        runtimeSampleCollection.insert(sample);
    }

    @Override
    public int bulkInsert(List<RuntimeSample> samples) {
        for (var sample : samples) {
            runtimeSampleCollection.insert(sample);
        }
        return samples.size();
    }

    @Override
    public List<RuntimeSample> range(ZonedDateTime from, ZonedDateTime to, int limit) {
        var query = new Query();
        query.filter = Filters.and(Filters.gte("collected_at", from), Filters.lte("collected_at", to));
        query.limit = limit;
        return runtimeSampleCollection.find(query);
    }
}
