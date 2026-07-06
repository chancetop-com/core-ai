package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author stephen
 */
public class SchemaMigrationVGatewayModelModelIdIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260706001";
    }

    @Override
    public String description() {
        return "add gateway_model index on model_id for model validation lookups (notablescan compliance)";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("gateway_model", ascending("model_id"));
    }
}
