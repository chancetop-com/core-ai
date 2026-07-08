package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Creates indexes for memory experiment collections.
 *
 * @author stephen
 */
public class SchemaMigrationVMemoryExperimentIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260708002";
    }

    @Override
    public String description() {
        return "create indexes for agent_memory_experiment_configs and agent_memory_experiment_runs";
    }

    @Override
    public void migrate(Mongo mongo) {
        // configs: one per agent
        mongo.createIndex("agent_memory_experiment_configs",
                Indexes.ascending("agent_id"),
                new IndexOptions().unique(true));

        // runs: query by agent + time for analytics
        mongo.createIndex("agent_memory_experiment_runs",
                Indexes.compoundIndex(Indexes.ascending("agent_id"), Indexes.descending("created_at")));
        // runs: patch outcome by session_id
        mongo.createIndex("agent_memory_experiment_runs",
                Indexes.compoundIndex(Indexes.descending("created_at"), Indexes.ascending("session_id")));
        // runs: lookup by run_id for attribution patching
        mongo.createIndex("agent_memory_experiment_runs",
                Indexes.ascending("run_id"));
    }
}
