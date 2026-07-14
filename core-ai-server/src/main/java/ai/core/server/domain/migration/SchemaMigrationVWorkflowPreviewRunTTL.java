package ai.core.server.domain.migration;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

import java.util.concurrent.TimeUnit;

/**
 * Co-expire draft-preview ("Test") runs with their version snapshot. Preview versions already TTL a day after
 * creation (see {@link SchemaMigrationVWorkflowPreviewIndexFix}), but preview runs persisted forever — so run
 * history kept showing test runs whose graph snapshot was already gone, and opening one 404'd on the graph
 * endpoint. These TTLs drop preview runs and their node-runs a day after creation; the partial filter on
 * preview=true leaves real (published-version) runs untouched.
 *
 * @author Xander
 */
public class SchemaMigrationVWorkflowPreviewRunTTL implements SchemaMigration {
    private static final long ONE_DAY_SECONDS = 86400L;

    @Override
    public String version() {
        return "20260615001";
    }

    @Override
    public String description() {
        return "TTL preview workflow runs and node-runs in step with their preview version";
    }

    @Override
    public void migrate(Mongo mongo) {
        var options = new IndexOptions().expireAfter(ONE_DAY_SECONDS, TimeUnit.SECONDS)
            .partialFilterExpression(Filters.eq("preview", Boolean.TRUE));
        mongo.createIndex("workflow_runs", Indexes.ascending("created_at"), options);
        mongo.createIndex("workflow_node_runs", Indexes.ascending("created_at"),
            new IndexOptions().expireAfter(ONE_DAY_SECONDS, TimeUnit.SECONDS).partialFilterExpression(Filters.eq("preview", Boolean.TRUE)));
    }
}
