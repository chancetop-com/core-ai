package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVMemoryLayerIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260706001";
    }

    @Override
    public String description() {
        return "create layer index on agent_memories for V2 three-tier memory architecture";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("agent_memories", Indexes.compoundIndex(
                Indexes.ascending("agent_id"),
                Indexes.ascending("layer"),
                Indexes.ascending("created_at")
        ));
    }
}
