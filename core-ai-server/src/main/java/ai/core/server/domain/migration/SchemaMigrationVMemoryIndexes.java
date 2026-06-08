package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVMemoryIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260608001";
    }

    @Override
    public String description() {
        return "create indexes for agent_memories and agent_memory_extraction_cursors";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("agent_memory_extraction_cursors", Indexes.ascending("agent_id"));

        mongo.createIndex("agent_memories", Indexes.ascending("agent_id"));
        mongo.createIndex("agent_memories", Indexes.compoundIndex(Indexes.ascending("agent_id"), Indexes.ascending("created_at")));
    }
}
