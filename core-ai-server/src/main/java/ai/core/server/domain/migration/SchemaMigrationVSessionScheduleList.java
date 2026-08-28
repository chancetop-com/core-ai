package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Backs the admin session schedules listing (sort by created_at desc). Without an
 * index the sorted full scan fails under notablescan (Mongo error 291).
 *
 * @author stephen
 */
public class SchemaMigrationVSessionScheduleList implements SchemaMigration {
    @Override
    public String version() {
        return "20260828002";
    }

    @Override
    public String description() {
        return "create index for session_schedules admin listing";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("session_schedules", Indexes.ascending("created_at"));
    }
}
