package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Index backing the {@code media_id="last"} lookup: newest completed media of a modality in a
 * session, owned by the caller.
 *
 * @author Stephen
 */
public class SchemaMigrationVMediaJobReferenceIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260830001";
    }

    @Override
    public String description() {
        return "create media job reference lookup indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("media_jobs", Indexes.ascending("session_id", "media_type", "state", "completed_at"));
        mongo.createIndex("media_jobs", Indexes.ascending("user_id", "media_type", "state", "completed_at"));
    }
}
