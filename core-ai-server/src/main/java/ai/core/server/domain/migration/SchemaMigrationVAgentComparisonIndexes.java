package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVAgentComparisonIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20261013001";
    }

    @Override
    public String description() {
        return "create agent_comparisons / comparison_votes indexes and chat_sessions comparison lookup indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("agent_comparisons", Indexes.ascending("agent_id", "created_at"));
        mongo.createIndex("agent_comparisons", Indexes.ascending("created_by", "created_at"));
        // idempotent vote anchor: one vote per (comparison, session_a, turn); re-voting replaces it
        mongo.createIndex("comparison_votes",
                Indexes.ascending("comparison_id", "session_a", "turn"),
                new IndexOptions().unique(true));
        mongo.createIndex("chat_sessions", Indexes.ascending("comparison_id"));
        mongo.createIndex("chat_sessions", Indexes.ascending("comparison_pair_id"));
    }
}
