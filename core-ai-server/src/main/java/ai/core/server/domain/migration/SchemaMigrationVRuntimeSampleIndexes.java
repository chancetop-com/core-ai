package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class SchemaMigrationVRuntimeSampleIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20261014001";
    }

    @Override
    public String description() {
        return "add runtime_samples indexes for per-pod series and 30d expiry";
    }

    @Override
    public void migrate(Mongo mongo) {
        // dashboard queries: one pod's series, and "latest sample per pod" across the fleet
        mongo.createIndex("runtime_samples", Indexes.compoundIndex(Indexes.ascending("pod"), Indexes.descending("collected_at")));
        // range scans over a time window, and 30d auto-expiry (TTL thread runs every 60s)
        mongo.createIndex("runtime_samples", Indexes.ascending("collected_at"),
                new IndexOptions().expireAfter(2592000L, TimeUnit.SECONDS));
    }
}
