package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVTraceListFilterIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260610001";
    }

    @Override
    public String description() {
        return "create trace indexes for list filters, count and facet aggregation";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("traces", Indexes.descending("started_at"));
        mongo.createIndex("traces", Indexes.ascending("model"));
        mongo.createIndex("traces", Indexes.ascending("agent_name"));
        mongo.createIndex("traces", Indexes.ascending("type"));
        mongo.createIndex("traces", Indexes.ascending("source"));
    }
}
