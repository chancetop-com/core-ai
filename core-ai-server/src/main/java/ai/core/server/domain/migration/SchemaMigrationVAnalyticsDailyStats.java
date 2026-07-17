package ai.core.server.domain.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Creates analytics_daily_stats collection with compound indexes for admin analytics dashboard queries.
 *
 * @author core-ai
 */
public class SchemaMigrationVAnalyticsDailyStats implements SchemaMigration {
    @Override
    public String version() {
        return "20260717001";
    }

    @Override
    public String description() {
        return "create analytics_daily_stats collection with indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        // Compound index for date-based queries per dimension
        try {
            mongo.createIndex("analytics_daily_stats",
                Indexes.compoundIndex(
                    Indexes.descending("date"),
                    Indexes.ascending("source")),
                new IndexOptions().background(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }

        try {
            mongo.createIndex("analytics_daily_stats",
                Indexes.compoundIndex(
                    Indexes.descending("date"),
                    Indexes.ascending("agent_id")),
                new IndexOptions().background(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }

        try {
            mongo.createIndex("analytics_daily_stats",
                Indexes.compoundIndex(
                    Indexes.descending("date"),
                    Indexes.ascending("model")),
                new IndexOptions().background(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }

        try {
            mongo.createIndex("analytics_daily_stats",
                Indexes.compoundIndex(
                    Indexes.descending("date"),
                    Indexes.ascending("user_id")),
                new IndexOptions().background(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }

        try {
            mongo.createIndex("analytics_daily_stats",
                Indexes.compoundIndex(
                    Indexes.descending("date"),
                    Indexes.ascending("provider_id")),
                new IndexOptions().background(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }

        // Standalone date index for count queries
        try {
            mongo.createIndex("analytics_daily_stats",
                Indexes.descending("date"),
                new IndexOptions().background(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }
    }
}
