package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author core-ai
 */
public class SchemaMigrationVSessionFeedbackIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260708001";
    }

    @Override
    public String description() {
        return "create indexes for session_feedback";
    }

    @Override
    public void migrate(Mongo mongo) {
        // one feedback per session per user — query by session_id is the primary access pattern
        mongo.createIndex("session_feedback",
            Indexes.compoundIndex(Indexes.ascending("session_id"), Indexes.ascending("user_id")),
            new IndexOptions().unique(true));
        mongo.createIndex("session_feedback", Indexes.descending("created_at"));
        // agent-level aggregate queries for analytics
        mongo.createIndex("session_feedback", Indexes.compoundIndex(Indexes.ascending("agent_id"), Indexes.descending("created_at")));
    }
}
