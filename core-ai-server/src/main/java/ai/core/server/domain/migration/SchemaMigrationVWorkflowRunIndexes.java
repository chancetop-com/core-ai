package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Ensures the workflow_runs / workflow_node_runs / workflow_published_versions indexes exist. Like the
 * definitions index, these were added to SchemaMigrationVWorkflowIndexes after it was recorded as applied, so the
 * manager skipped them and they were never created on existing environments. Without them the runner job's claim
 * query (status, lease_until) and the node-run fold (run_id) fail on a notablescan Mongo and runs stay PENDING.
 *
 * <p>All non-unique on purpose: node-run identity is enforced by the deterministic _id (run|node|scope), and
 * preview snapshots all use version=0 so a unique (workflow_id, version) would reject the second preview.
 *
 * @author Xander
 */
public class SchemaMigrationVWorkflowRunIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260608002";
    }

    @Override
    public String description() {
        return "create workflow_runs / node_runs / published_versions indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        // claim/sweep: PENDING/RUNNING runs whose lease has expired; plus list-by-workflow/user and recent-first
        mongo.createIndex("workflow_runs", Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("lease_until")));
        mongo.createIndex("workflow_runs", Indexes.ascending("workflow_id"));
        mongo.createIndex("workflow_runs", Indexes.ascending("user_id"));
        mongo.createIndex("workflow_runs", Indexes.descending("created_at"));

        // fold all node-runs of a run (run_id prefix); plus the AGENT/LLM child-run link
        mongo.createIndex("workflow_node_runs",
            Indexes.compoundIndex(Indexes.ascending("run_id"), Indexes.ascending("node_id"), Indexes.ascending("scope_path_key")));
        mongo.createIndex("workflow_node_runs", Indexes.ascending("child_run_id"));

        // nextVersion scans by workflow_id
        mongo.createIndex("workflow_published_versions", Indexes.ascending("workflow_id"));
    }
}
