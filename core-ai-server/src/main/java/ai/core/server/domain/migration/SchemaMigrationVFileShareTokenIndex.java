package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVFileShareTokenIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260604002";
    }

    @Override
    public String description() {
        return "create share token index for file_records";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("file_records", Indexes.ascending("share_token"), new IndexOptions().unique(true).sparse(true));
    }
}
