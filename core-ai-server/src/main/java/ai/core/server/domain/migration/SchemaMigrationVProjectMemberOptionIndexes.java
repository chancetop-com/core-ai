package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * The (visibility, status, published_version_id) compound index on workflow_definitions is required by the
 * project member-options shared-workflow query. It is normally created by SchemaMigrationVWorkflowVisibilityStatusIndex,
 * but environments whose schema_migrations were seeded from elsewhere can miss it (notablescan 291 observed locally).
 * createIndex is idempotent.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectMemberOptionIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260813003";
    }

    @Override
    public String description() {
        return "ensure workflow_definitions (visibility, status, published_version_id) index for project member options";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("workflow_definitions",
            Indexes.compoundIndex(Indexes.ascending("visibility"), Indexes.ascending("status"), Indexes.ascending("published_version_id")));
    }
}
