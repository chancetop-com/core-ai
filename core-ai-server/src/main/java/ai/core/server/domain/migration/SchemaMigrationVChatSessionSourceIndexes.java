package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVChatSessionSourceIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260416001";
    }

    @Override
    public String description() {
        return "create indexes for chat_sessions source/agent/schedule lookups";
    }

    @Override
    public void migrate(Mongo mongo) {
        // primary Chat sidebar path: user + source filter + recency sort
        mongo.createIndex("chat_sessions",
            Indexes.compoundIndex(
                Indexes.ascending("user_id"),
                Indexes.ascending("source"),
                Indexes.descending("last_message_at")));

        // Agent Test Runs tab: filter by agent + source
        mongo.createIndex("chat_sessions",
            Indexes.compoundIndex(
                Indexes.ascending("agent_id"),
                Indexes.ascending("source"),
                Indexes.descending("last_message_at")));

        // Scheduler Runs tab: filter by schedule
        mongo.createIndex("chat_sessions",
            Indexes.compoundIndex(
                Indexes.ascending("schedule_id"),
                Indexes.descending("last_message_at")));
    }
}
