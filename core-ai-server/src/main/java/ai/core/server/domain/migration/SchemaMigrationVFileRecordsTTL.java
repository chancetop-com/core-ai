package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class SchemaMigrationVFileRecordsTTL implements SchemaMigration {
    @Override
    public String version() {
        return "20260430001";
    }

    @Override
    public String description() {
        return "add TTL index on file_records.created_at for 24h auto-expiry";
    }

    @Override
    public void migrate(Mongo mongo) {
        // Drop existing non-TTL index on created_at if it exists, then create TTL index
        mongo.dropIndex("file_records", Indexes.ascending("created_at"));
        // TTL index: documents auto-delete 24 hours after created_at
        // MongoDB's TTL thread runs every 60s, so deletion may be slightly delayed
        mongo.createIndex("file_records",
            Indexes.ascending("created_at"),
            new IndexOptions().expireAfter(86400L, TimeUnit.SECONDS));
    }
}
