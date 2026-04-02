package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author stephen
 */
public class SchemaMigrationVServiceApiIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260402001";
    }

    @Override
    public String description() {
        return "create service_api indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        // Index for getByName query: Filters.eq("name", name)
        mongo.createIndex("service_api", ascending("name"));
    }
}
