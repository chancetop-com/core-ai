package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVChatSessionCreatedAtIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260622001";
    }

    @Override
    public String description() {
        return "create index for chat_sessions stable creation-order sort";
    }

    @Override
    public void migrate(Mongo mongo) {
        // backs the Chat sidebar list: user_id filter + created_at sort, so active sessions keep their position
        mongo.createIndex("chat_sessions",
            Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("created_at")));
    }
}
