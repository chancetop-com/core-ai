package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author stephen
 */
public class SchemaMigrationVToolRegistryTypeNameIndex implements SchemaMigration {
    @Override
    public String version() {
        // 20260630001 was also used by an older trace_daily_stats migration in Dev.
        // Dev therefore recorded that version without creating this index. A fresh
        // version makes the idempotent createIndex run in every environment.
        return "20260812001";
    }

    @Override
    public String description() {
        return "ensure tool_registry compound index on (type, name) for notablescan compliance";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("tool_registry", ascending("type", "name"));
    }
}
