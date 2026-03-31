package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.descending;

/**
 * @author Xander
 */
public class SchemaMigrationVSystemPromptIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260331001";
    }

    @Override
    public String description() {
        return "create system_prompts indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("system_prompts", ascending("prompt_id"));
        mongo.createIndex("system_prompts", ascending("prompt_id", "version"));
        mongo.createIndex("system_prompts", ascending("user_id"));
        mongo.createIndex("system_prompts", descending("created_at"));
    }
}
