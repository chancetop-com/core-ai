package ai.core.server.domain.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Creates trace_daily_stats collection with indexes for Dashboard token usage queries.
 *
 * @author cyril
 */
public class SchemaMigrationVTraceDailyStats implements SchemaMigration {
    @Override
    public String version() {
        return "20260626001";
    }

    @Override
    public String description() {
        return "create trace_daily_stats collection with indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        // Unique compound index on (user_id, date) — one row per user per day
        try {
            mongo.createIndex("trace_daily_stats",
                Indexes.compoundIndex(
                    Indexes.ascending("user_id"),
                    Indexes.ascending("date")),
                new IndexOptions().unique(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e; // 85 = IndexOptionsConflict
        }

        // Descending date index for range queries (e.g. "last 30 days")
        try {
            mongo.createIndex("trace_daily_stats",
                Indexes.descending("date"));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }
    }
}
