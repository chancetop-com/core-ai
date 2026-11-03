package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author Stephen
 */
public class SchemaMigrationVMediaJobArtifact implements SchemaMigration {
    @Override
    public String version() {
        return "20261103002";
    }

    @Override
    public String description() {
        return "create media job artifact indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("media_jobs", Indexes.ascending("media_type", "state", "file_id", "completed_at"));
        mongo.createIndex("media_jobs", Indexes.ascending("media_type", "state", "updated_at"));
        mongo.createIndex("media_jobs", Indexes.ascending("artifact_state", "artifact_claimed_at"));
        mongo.createIndex("media_jobs", Indexes.ascending("artifact_state", "artifact_next_attempt_at"));
    }
}
