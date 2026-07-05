package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author stephen
 */
public class SchemaMigrationVGatewayModelProviderIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260705001";
    }

    @Override
    public String description() {
        return "add gateway_model index on provider_id for model discovery/import";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("gateway_model", ascending("provider_id"));
    }
}
