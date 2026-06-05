package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;

/**
 * @author Xander
 */
public class SchemaMigrationVFixShareTokenIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260605001";
    }

    @Override
    public String description() {
        return "fix share_token unique index to skip null values using partialFilterExpression";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.dropIndex("file_records", Indexes.ascending("share_token"));
        mongo.createIndex("file_records", Indexes.ascending("share_token"),
                new IndexOptions().unique(true)
                        .partialFilterExpression(new Document("share_token", new Document("$type", "string"))));
    }
}
