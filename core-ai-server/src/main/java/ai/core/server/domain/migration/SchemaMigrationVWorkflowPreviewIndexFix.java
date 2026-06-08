package ai.core.server.domain.migration;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

import java.util.concurrent.TimeUnit;

/**
 * Makes draft-preview runs safe on environments that already have the legacy unique (workflow_id, version) index
 * from the original workflow migration. Preview snapshots all use version=0, so a surviving unique index rejects
 * the second preview of any workflow (E11000). The earlier index migrations only ADD non-unique indexes (same
 * keys + different options is rejected with code 86 and merely logged), so the unique index must be dropped
 * explicitly. Also adds a TTL so preview snapshots don't accumulate forever (published versions are preview=false).
 *
 * @author Xander
 */
public class SchemaMigrationVWorkflowPreviewIndexFix implements SchemaMigration {
    @Override
    public String version() {
        return "20260608003";
    }

    @Override
    public String description() {
        return "drop legacy unique (workflow_id, version) index and TTL preview snapshots";
    }

    @Override
    public void migrate(Mongo mongo) {
        try {
            mongo.dropIndex("workflow_published_versions",
                Indexes.compoundIndex(Indexes.ascending("workflow_id"), Indexes.ascending("version")));
        } catch (RuntimeException e) {
            // index not present (fresh environment) — nothing to drop
        }
        // expire preview snapshots a day after creation; the partial filter leaves real published versions untouched
        mongo.createIndex("workflow_published_versions", Indexes.ascending("published_at"),
            new IndexOptions().expireAfter(86400L, TimeUnit.SECONDS).partialFilterExpression(Filters.eq("preview", true)));
    }
}
