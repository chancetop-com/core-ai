package ai.core.server.domain.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.conversions.Bson;

/**
 * Creates analytics_daily_stats collection with compound indexes for admin analytics dashboard queries.
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
        createIndexIfNotExist(mongo, Indexes.compoundIndex(Indexes.descending("date"), Indexes.ascending("source")));
        createIndexIfNotExist(mongo, Indexes.compoundIndex(Indexes.descending("date"), Indexes.ascending("agent_id")));
        createIndexIfNotExist(mongo, Indexes.compoundIndex(Indexes.descending("date"), Indexes.ascending("model")));
        createIndexIfNotExist(mongo, Indexes.compoundIndex(Indexes.descending("date"), Indexes.ascending("user_id")));
        createIndexIfNotExist(mongo, Indexes.compoundIndex(Indexes.descending("date"), Indexes.ascending("provider_id")));
        createIndexIfNotExist(mongo, Indexes.descending("date"));
    }

    private void createIndexIfNotExist(Mongo mongo, Bson keys) {
        try {
            mongo.createIndex("analytics_daily_stats", keys, new IndexOptions().background(true));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != 85) throw e;
        }
    }
}
