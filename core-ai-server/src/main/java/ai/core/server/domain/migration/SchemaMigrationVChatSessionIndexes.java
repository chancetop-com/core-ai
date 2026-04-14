package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVChatSessionIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260414002";
    }

    @Override
    public String description() {
        return "create indexes for chat_sessions";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("chat_sessions",
            Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("last_message_at")));
    }
}
