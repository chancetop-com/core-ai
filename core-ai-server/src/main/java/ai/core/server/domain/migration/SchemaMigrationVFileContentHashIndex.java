package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVFileContentHashIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260811001";
    }

    @Override
    public String description() {
        return "create content hash index for file_records artifact dedup";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("file_records",
            Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.ascending("content_hash")));
    }
}
