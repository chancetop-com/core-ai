package ai.core.server.domain.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Adds a standalone date index on trace_daily_stats so countStatsForDate() works with notablescan.
 *
 * @author cyril
 */
public class SchemaMigrationVTraceDailyStatsDateIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260709001";
    }

    @Override
    public String description() {
        return "add trace_daily_stats date index for stats coverage checks";
    }

    @Override
    public void migrate(Mongo mongo) {
        try {
            mongo.createIndex("trace_daily_stats", Indexes.descending("date"));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e; // 85 = IndexOptionsConflict
        }
    }
}
