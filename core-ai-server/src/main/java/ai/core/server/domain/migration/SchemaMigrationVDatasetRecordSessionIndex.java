package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;
import org.bson.Document;

/**
 * @author stephen
 */
public class SchemaMigrationVDatasetRecordSessionIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260805001";
    }

    @Override
    public String description() {
        return "add partial unique (dataset_id, session_id) index on dataset_records for session state";
    }

    @Override
    public void migrate(Mongo mongo) {
        // partial index: only records with session_id are constrained to be unique,
        // existing run records without session_id stay unaffected
        var options = new IndexOptions().unique(true)
                .partialFilterExpression(new Document("session_id", new Document("$type", "string")));
        mongo.createIndex("dataset_records",
                Indexes.compoundIndex(Indexes.ascending("dataset_id"), Indexes.ascending("session_id")),
                options);
    }
}
