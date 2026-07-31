package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVGeminiVideoIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260731001";
    }

    @Override
    public String description() {
        return "create Gemini video attachment indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("session_attachment_ref", Indexes.ascending("session_id"));
        var uniqueOptions = new IndexOptions().unique(true);
        mongo.createIndex("gemini_files", Indexes.ascending("user_id", "provider_id", "upstream_model", "container", "blob_name", "source_etag"), uniqueOptions);
        mongo.createIndex("gemini_files", Indexes.ascending("state", "provider_id", "updated_at"));
        mongo.createIndex("gemini_files", Indexes.ascending("expires_at"));
    }
}
