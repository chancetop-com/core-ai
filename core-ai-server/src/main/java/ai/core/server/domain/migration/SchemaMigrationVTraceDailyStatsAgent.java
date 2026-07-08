package ai.core.server.domain.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Rebuilds trace_daily_stats indexes to include agent_id dimension.
 * Drops old (user_id, date) unique index; creates (user_id, agent_id, date).
 * Existing data is cleared — it will be rebuilt by TraceDailyMaintenanceJob.
 *
 * @author cyril
 */
public class SchemaMigrationVTraceDailyStatsAgent implements SchemaMigration {
    @Override
    public String version() {
        return "20260008010";
    }

    @Override
    public String description() {
        return "rebuild trace_daily_stats indexes with agent_id dimension";
    }

    @Override
    public void migrate(Mongo mongo) {
        // Drop old unique index (user_id, date) — we replace it with (user_id, agent_id, date)
        try {
            mongo.dropIndex("trace_daily_stats",
                Indexes.compoundIndex(
                    Indexes.ascending("user_id"),
                    Indexes.ascending("date")));
        } catch (Exception ignored) {
            // Index may not exist
        }

        // Also drop the agent_name variant if it was created by previous migration
        try {
            mongo.dropIndex("trace_daily_stats",
                Indexes.compoundIndex(
                    Indexes.ascending("user_id"),
                    Indexes.ascending("agent_name"),
                    Indexes.ascending("date")));
        } catch (Exception ignored) {
            // Index may not exist
        }

        // Unique compound index on (user_id, agent_id, date) — one row per user per agent per day
        try {
            mongo.createIndex("trace_daily_stats",
                Indexes.compoundIndex(
                    Indexes.ascending("user_id"),
                    Indexes.ascending("agent_id"),
                    Indexes.ascending("date")),
                new IndexOptions().unique(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e; // 85 = IndexOptionsConflict
        }

        // Compound index for per-user date-range queries
        try {
            mongo.createIndex("trace_daily_stats",
                Indexes.compoundIndex(
                    Indexes.ascending("user_id"),
                    Indexes.descending("date")));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }
    }
}
