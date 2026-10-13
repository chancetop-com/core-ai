package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVAgentVersionIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20261013002";
    }

    @Override
    public String description() {
        return "create agent_versions lookup index";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("agent_versions", Indexes.ascending("agent_id", "created_at"));
    }
}
