package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVChatMessageIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260414001";
    }

    @Override
    public String description() {
        return "create indexes for chat_messages";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("chat_messages",
            Indexes.compoundIndex(Indexes.ascending("session_id"), Indexes.ascending("seq")),
            new IndexOptions().unique(true));
        mongo.createIndex("chat_messages", Indexes.descending("created_at"));
    }
}
