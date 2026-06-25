package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVWorkflowVisibilityStatusIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260625001";
    }

    @Override
    public String description() {
        return "index workflow visibility/status for public discover and archived filtering";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("workflow_definitions",
            Indexes.compoundIndex(Indexes.ascending("visibility"), Indexes.ascending("status"), Indexes.ascending("published_version_id")));
        mongo.createIndex("workflow_published_versions",
            Indexes.compoundIndex(Indexes.ascending("workflow_id"), Indexes.ascending("preview"), Indexes.ascending("status")));
    }
}
