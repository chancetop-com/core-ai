package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author stephen
 */
public class SchemaMigrationVUsersIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260420001";
    }

    @Override
    public String description() {
        return "create users indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("users", ascending("email"));
    }
}
