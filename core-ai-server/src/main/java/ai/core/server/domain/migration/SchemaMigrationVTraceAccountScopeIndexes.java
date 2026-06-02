package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVTraceAccountScopeIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260602001";
    }

    @Override
    public String description() {
        return "create account-scoped trace and span indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("traces", Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("created_at")));
        mongo.createIndex("traces", Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("started_at")));
        mongo.createIndex("spans", Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.ascending("type"), Indexes.descending("started_at")));
    }
}
