package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author stephen
 */
public class SchemaMigrationVTriggerIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260425001";
    }

    @Override
    public String description() {
        return "create triggers indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("triggers", ascending("type"));
    }
}
