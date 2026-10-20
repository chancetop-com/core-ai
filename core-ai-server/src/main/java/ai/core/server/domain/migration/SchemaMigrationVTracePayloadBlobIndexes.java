package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Index trace payload blobs by trace so archived traces can drop their payloads in one query.
 *
 * @author stephen
 */
public class SchemaMigrationVTracePayloadBlobIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20261020001";
    }

    @Override
    public String description() {
        return "index trace_payload_blobs by trace_id for archive cleanup";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("trace_payload_blobs", Indexes.ascending("trace_id"));
    }
}
