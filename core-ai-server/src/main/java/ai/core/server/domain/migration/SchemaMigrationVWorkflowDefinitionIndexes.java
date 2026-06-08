package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Ensures the workflow_definitions list-by-user index exists. It was added to SchemaMigrationVWorkflowIndexes
 * after that migration was already recorded as applied, so the manager skipped it and the index was never
 * created on existing environments — list-by-user then fails on a notablescan Mongo. createIndex is idempotent.
 *
 * <p>Deliberately NOT unique on (user_id, name): workflow names are not unique (every new workflow starts as
 * "Untitled workflow", like Dify), so a unique index would reject the second create and break index builds on
 * existing duplicate rows.
 *
 * @author Xander
 */
public class SchemaMigrationVWorkflowDefinitionIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260608001";
    }

    @Override
    public String description() {
        return "create the workflow_definitions list-by-user index (user_id)";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("workflow_definitions", Indexes.ascending("user_id"));
    }
}
