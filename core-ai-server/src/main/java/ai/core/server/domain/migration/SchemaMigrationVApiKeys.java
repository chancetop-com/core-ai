package ai.core.server.domain.migration;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVApiKeys implements SchemaMigration {
    @Override
    public String version() {
        return "20260804001";
    }

    @Override
    public String description() {
        return "api user access: api_keys collection indexes, users owner_id+external_id partial unique index, traces user_id+created_at index";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("api_keys", Indexes.ascending("key_hash"), new IndexOptions().unique(true));
        mongo.createIndex("api_keys", Indexes.ascending("user_id", "scope"));
        mongo.createIndex("api_keys", Indexes.ascending("expires_at"));

        // core-ng EntityEncoder writes null fields explicitly; sparse unique would index null owner_id/external_id
        // on legacy internal users and conflict. Partial index only covers docs where both are strings.
        var partial = new IndexOptions().unique(true)
                .partialFilterExpression(Filters.and(Filters.type("owner_id", "string"), Filters.type("external_id", "string")));
        mongo.createIndex("users", Indexes.ascending("owner_id", "external_id"), partial);

        mongo.createIndex("traces", Indexes.ascending("user_id", "created_at"));
    }
}
