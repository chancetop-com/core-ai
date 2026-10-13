package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVAgentComparisonListIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20261013003";
    }

    @Override
    public String description() {
        return "list agent_comparisons across agents: created_at and participant lookup indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("agent_comparisons", Indexes.descending("created_at"));
        mongo.createIndex("agent_comparisons", Indexes.ascending("participant_user_ids", "created_at"));
    }
}
