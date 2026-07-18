package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author Stephen
 */
public class SchemaMigrationVMediaJobIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260718001";
    }

    @Override
    public String description() {
        return "create media job indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("media_jobs", Indexes.ascending("provider_id", "upstream_video_id"));
        mongo.createIndex("media_jobs", Indexes.ascending("user_id", "created_at"));
        mongo.createIndex("media_jobs", Indexes.ascending("session_id", "created_at"));
        mongo.createIndex("media_jobs", Indexes.ascending("agent_run_id", "created_at"));
    }
}
