package ai.core.server.domain.migration;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Rebuilds users.email as a partial unique index: core-ng writes null fields explicitly,
 * and api users have no email — a plain unique index rejects the second null email (E11000),
 * blocking creation of more than one api user per environment.
 *
 * @author core-ai
 */
public class SchemaMigrationVEmailPartialUniqueIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260808002";
    }

    @Override
    public String description() {
        return "rebuild users.email unique index as partial (string-only) to allow null emails for api users";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.dropIndex("users", Indexes.ascending("email"));
        mongo.createIndex("users", Indexes.ascending("email"),
                new IndexOptions().unique(true).partialFilterExpression(Filters.type("email", "string")));
    }
}
