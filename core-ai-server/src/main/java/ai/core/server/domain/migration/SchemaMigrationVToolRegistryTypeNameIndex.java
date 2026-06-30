package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author stephen
 */
public class SchemaMigrationVToolRegistryTypeNameIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260630001";
    }

    @Override
    public String description() {
        return "add tool_registry compound index on (type, name) for notablescan compliance";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("tool_registry", ascending("type", "name"));
    }
}
