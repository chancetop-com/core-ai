package ai.core.server.runtime;

import ai.core.server.pg.PagedBackfill;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

/**
 * Copies legacy Mongo runtime samples into PostgreSQL. Samples are append-only and cheap to re-read, so
 * the page is larger than the trace backfill's.
 *
 * @author stephen
 */
public class RuntimeSampleMigrationService extends PagedBackfill<RuntimeSample> {
    private static final String MARKER = "runtime_samples_v1";
    private static final String LOCK_KEY = "runtime_sample_migration";
    private static final int PAGE_SIZE = 500;

    @Inject
    MongoCollection<RuntimeSample> sampleCollection;
    @Inject
    RuntimeSampleStore sampleStore;

    @Override
    protected String name() {
        return "runtime-sample-migration";
    }

    @Override
    protected String marker() {
        return MARKER;
    }

    @Override
    protected String lockKey() {
        return LOCK_KEY;
    }

    @Override
    protected List<RuntimeSample> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        return sampleCollection.find(query);
    }

    @Override
    protected String key(RuntimeSample row) {
        return row.id;
    }

    @Override
    protected void write(List<RuntimeSample> page) {
        sampleStore.bulkInsert(page);
    }

    @Override
    protected void writeOne(RuntimeSample row) {
        sampleStore.insert(row);
    }
}
