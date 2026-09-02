package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

/**
 * @author stephen
 */
public class SchemaMigrationVSystemPromptNameIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260902001";
    }

    @Override
    public String description() {
        return "add system_prompts index on name+version for get-by-name lookups (notablescan compliance)";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("system_prompts", compoundIndex(ascending("name"), descending("version")));
    }
}
