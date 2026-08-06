package ai.core.server.domain.migration;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Replaces the legacy sparse unique api_key index with a partial unique one.
 * core-ng writes null fields explicitly, so sparse indexes also index api_key=null —
 * api users (which have no users.api_key) would collide on the unique sparse index.
 *
 * @author core-ai
 */
public class SchemaMigrationVApiUserApiKeyIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260804002";
    }

    @Override
    public String description() {
        return "rebuild users.api_key unique index as partial (string-only) to allow api users without api_key";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.dropIndex("users", Indexes.ascending("api_key"));
        var partial = new IndexOptions().unique(true)
                .partialFilterExpression(Filters.type("api_key", "string"));
        mongo.createIndex("users", Indexes.ascending("api_key"), partial);
    }
}
