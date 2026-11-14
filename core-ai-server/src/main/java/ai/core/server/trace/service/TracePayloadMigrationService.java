package ai.core.server.trace.service;

import ai.core.server.pg.PagedBackfill;
import ai.core.server.trace.domain.TracePayloadBlob;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

/**
 * Copies the deduplicated payload blobs out of Mongo. A blob is one inline image, so the page stays
 * small: a handful of rows can already be tens of megabytes of base64.
 *
 * @author stephen
 */
public class TracePayloadMigrationService extends PagedBackfill<TracePayloadBlob> {
    private static final String MARKER = "trace_payloads_v1";
    private static final String LOCK_KEY = "trace_payload_migration";
    private static final int PAGE_SIZE = 10;

    @Inject
    MongoCollection<TracePayloadBlob> blobCollection;
    @Inject
    TracePayloadRepository payloadRepository;

    @Override
    protected String name() {
        return "trace-payload-migration";
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
    protected List<TracePayloadBlob> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        return blobCollection.find(query);
    }

    @Override
    protected String key(TracePayloadBlob row) {
        return row.id;
    }

    @Override
    protected void write(List<TracePayloadBlob> page) {
        for (var blob : page) {
            payloadRepository.store(blob);
        }
    }

    @Override
    protected void writeOne(TracePayloadBlob row) {
        payloadRepository.store(row);
    }
}
