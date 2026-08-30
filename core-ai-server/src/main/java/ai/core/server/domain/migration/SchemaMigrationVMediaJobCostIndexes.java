package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author Stephen
 */
public class SchemaMigrationVMediaJobCostIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260829001";
    }

    @Override
    public String description() {
        return "create media job indexes for the cost list API";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("media_jobs", Indexes.descending("created_at"));
        mongo.createIndex("media_jobs", Indexes.compoundIndex(Indexes.ascending("media_type"), Indexes.descending("created_at")));
    }
}
