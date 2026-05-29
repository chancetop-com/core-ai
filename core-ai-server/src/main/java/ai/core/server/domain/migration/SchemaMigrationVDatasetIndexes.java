package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVDatasetIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260527001";
    }

    @Override
    public String description() {
        return "create collections and indexes for datasets and dataset_records";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("datasets", Indexes.ascending("name"));

        mongo.createIndex("dataset_records", Indexes.ascending("dataset_id"));
        mongo.createIndex("dataset_records", Indexes.descending("run_started_at"));
        mongo.createIndex("dataset_records", Indexes.compoundIndex(
            Indexes.ascending("dataset_id"),
            Indexes.descending("run_started_at")
        ));
    }
}
