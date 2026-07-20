package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;

public class SchemaMigrationVSharedArtifactIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260720001";
    }

    @Override
    public String description() {
        return "create shared artifact list indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        var options = new IndexOptions().partialFilterExpression(new Document("share_token", new Document("$type", "string")));
        mongo.createIndex("file_records", Indexes.descending("shared_at"), options);
        mongo.createIndex("file_records", Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("shared_at")), options);
    }
}
