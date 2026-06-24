package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Index workflow_runs by parent_run_id: cascade-cancel and the run-tree view both fan out from a parent to its
 * direct child runs, and a child wakes its parent by id. Without this the lookup is a collection scan (dev Mongo
 * runs with notablescan, so it would 500).
 *
 * @author Xander
 */
public class SchemaMigrationVWorkflowParentRunIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260624002";
    }

    @Override
    public String description() {
        return "index workflow_runs by parent_run_id for sub-workflow cascade and run tree";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("workflow_runs", Indexes.ascending("parent_run_id"));
    }
}
