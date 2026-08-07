package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVChatSessionAgentIdIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260807001";
    }

    @Override
    public String description() {
        return "create index for chat_sessions agent-id filtered list";
    }

    @Override
    public void migrate(Mongo mongo) {
        // backs the session list agent_ids filter: user_id + agent_id filter + created_at sort
        mongo.createIndex("chat_sessions",
            Indexes.compoundIndex(
                Indexes.ascending("user_id"),
                Indexes.ascending("agent_id"),
                Indexes.descending("created_at")));
    }
}
