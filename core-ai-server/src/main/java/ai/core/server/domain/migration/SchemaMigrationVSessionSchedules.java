package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVSessionSchedules implements SchemaMigration {
    @Override
    public String version() {
        return "20260821001";
    }

    @Override
    public String description() {
        return "create indexes for session_schedules due scan and session listing";
    }

    @Override
    public void migrate(Mongo mongo) {
        // backs the scheduler due scan: enabled + next_run_at filter
        mongo.createIndex("session_schedules",
            Indexes.compoundIndex(
                Indexes.ascending("enabled"),
                Indexes.ascending("next_run_at")));
        // backs the scheduled_task tool's session listing
        mongo.createIndex("session_schedules", Indexes.ascending("session_id"));
    }
}
