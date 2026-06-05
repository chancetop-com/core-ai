package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVWorkflowIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260605001";
    }

    @Override
    public String description() {
        return "create indexes for workflow_runs and workflow_node_runs";
    }

    @Override
    public void migrate(Mongo mongo) {
        // workflow definitions: list by user; name unique per user
        mongo.createIndex("workflow_definitions", Indexes.ascending("user_id"));
        mongo.createIndex("workflow_definitions",
            Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.ascending("name")),
            new IndexOptions().unique(true));

        // published versions: load by id (default); list by workflow; version unique per workflow
        mongo.createIndex("workflow_published_versions",
            Indexes.compoundIndex(Indexes.ascending("workflow_id"), Indexes.ascending("version")),
            new IndexOptions().unique(true));

        // claim/sweep query: find PENDING/RUNNING runs whose lease has expired
        mongo.createIndex("workflow_runs", Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("lease_until")));
        mongo.createIndex("workflow_runs", Indexes.ascending("workflow_id"));
        mongo.createIndex("workflow_runs", Indexes.ascending("user_id"));
        mongo.createIndex("workflow_runs", Indexes.descending("created_at"));

        // identity + race-safe insert: unique (run_id, node_id, scope_path_key). The run_id prefix also serves
        // the "load all node-runs of a run" fold, so no separate run_id index is needed.
        mongo.createIndex("workflow_node_runs",
            Indexes.compoundIndex(Indexes.ascending("run_id"), Indexes.ascending("node_id"), Indexes.ascending("scope_path_key")),
            new IndexOptions().unique(true));
        mongo.createIndex("workflow_node_runs", Indexes.ascending("child_run_id"));
    }
}
