package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Indexes workflow_definitions.published_version_id so the discover query (other users' published workflows:
 * user_id != caller AND published_version_id != null) runs as an index scan instead of a collection scan, which
 * a notablescan Mongo would otherwise reject. createIndex is idempotent.
 *
 * @author Xander
 */
public class SchemaMigrationVWorkflowPublicIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260624001";
    }

    @Override
    public String description() {
        return "index workflow_definitions.published_version_id for cross-user discover query";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("workflow_definitions", Indexes.ascending("published_version_id"));
    }
}
