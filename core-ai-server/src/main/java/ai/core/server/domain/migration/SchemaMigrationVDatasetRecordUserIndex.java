package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVDatasetRecordUserIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260601001";
    }

    @Override
    public String description() {
        return "add user_id index on dataset_records";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("dataset_records", Indexes.ascending("user_id"));
    }
}
