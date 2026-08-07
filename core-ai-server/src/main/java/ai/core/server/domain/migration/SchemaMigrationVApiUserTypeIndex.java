package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Adds the user_type index required by admin api-user listing (notablescan compliance on UAT/prod).
 *
 * @author core-ai
 */
public class SchemaMigrationVApiUserTypeIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260806003";
    }

    @Override
    public String description() {
        return "add users.user_type index for api user list queries (notablescan compliance)";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("users", Indexes.ascending("user_type"));
    }
}
