package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVSessionNoticeIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260928001";
    }

    @Override
    public String description() {
        return "create index for session_notices";
    }

    @Override
    public void migrate(Mongo mongo) {
        // serves both the unanswered lookup and the mark-answered update; without it every query is a
        // collection scan and notablescan environments reject it outright
        mongo.createIndex("session_notices", Indexes.compoundIndex(
            Indexes.ascending("user_id"),
            Indexes.ascending("channel_id"),
            Indexes.ascending("recipient"),
            Indexes.ascending("answered"),
            Indexes.descending("created_at")
        ));
    }
}
