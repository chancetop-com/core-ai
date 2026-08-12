package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Indexes for cost alert: unique per (rule, scope, date) event dedup and
 * agent-scoped trace aggregation.
 *
 * @author stephen
 */
public class SchemaMigrationVCostAlertIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260812002";
    }

    @Override
    public String description() {
        return "create cost alert dedup and agent trace aggregation indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        // scope_value is never null (empty string for global scope), so a plain unique index is safe
        mongo.createIndex("cost_alert_events",
            Indexes.compoundIndex(
                Indexes.ascending("rule_id"),
                Indexes.ascending("scope"),
                Indexes.ascending("scope_value"),
                Indexes.ascending("date")),
            new IndexOptions().unique(true));
        mongo.createIndex("traces",
            Indexes.compoundIndex(Indexes.ascending("agent_id"), Indexes.descending("started_at")));
    }
}
