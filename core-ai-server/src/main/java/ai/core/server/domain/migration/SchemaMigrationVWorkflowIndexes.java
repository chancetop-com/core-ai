package ai.core.server.domain.migration;

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
        // workflow definitions: list by user. Names are NOT unique (every workflow starts as "Untitled workflow",
        // like Dify), so there is intentionally no unique (user_id, name) index.
        mongo.createIndex("workflow_definitions", Indexes.ascending("user_id"));

        // published versions: load by id (default); nextVersion scans by workflow_id. NOT unique on
        // (workflow_id, version): preview snapshots all use version=0, so a unique index would reject the 2nd preview.
        mongo.createIndex("workflow_published_versions", Indexes.ascending("workflow_id"));

        // claim/sweep query: find PENDING/RUNNING runs whose lease has expired
        mongo.createIndex("workflow_runs", Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("lease_until")));
        mongo.createIndex("workflow_runs", Indexes.ascending("workflow_id"));
        mongo.createIndex("workflow_runs", Indexes.ascending("user_id"));
        mongo.createIndex("workflow_runs", Indexes.descending("created_at"));

        // fold all node-runs of a run by run_id prefix; identity is enforced by the deterministic _id
        // (run|node|scope), so this index is NOT unique (avoids any build conflict on existing data).
        mongo.createIndex("workflow_node_runs",
            Indexes.compoundIndex(Indexes.ascending("run_id"), Indexes.ascending("node_id"), Indexes.ascending("scope_path_key")));
        mongo.createIndex("workflow_node_runs", Indexes.ascending("child_run_id"));
    }
}
