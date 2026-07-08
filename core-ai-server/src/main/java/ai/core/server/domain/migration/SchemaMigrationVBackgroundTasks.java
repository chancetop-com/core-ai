package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Creates background_tasks collection with indexes for the task framework.
 *
 * @author cyril
 */
public class SchemaMigrationVBackgroundTasks implements SchemaMigration {
    @Override
    public String version() {
        return "20260629001";
    }

    @Override
    public String description() {
        return "create background_tasks collection with indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("background_tasks", Indexes.ascending("type"));
        mongo.createIndex("background_tasks", Indexes.ascending("status"));
    }
}
