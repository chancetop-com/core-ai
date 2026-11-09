package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgGatewayModelStore implements GatewayModelStore {
    private static final String TABLE = "app.gateway_model";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static GatewayModelConfig toModel(PgDocumentRow row) {
        return JsonUtil.fromJson(GatewayModelConfig.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<GatewayModelConfig> store() {
        return new PgDocumentStore<>(database, TABLE, GatewayModelConfig.class, model -> model.id);
    }

    @Override
    public List<GatewayModelConfig> listAll() {
        return database.select(SELECT + " ORDER BY data ->> ?", PgDocumentRow.class, "modelId")
                .stream().map(PgGatewayModelStore::toModel).toList();
    }

    @Override
    public Optional<GatewayModelConfig> get(String id) {
        return store().get(id);
    }

    @Override
    public List<GatewayModelConfig> listByProviderId(String providerId) {
        return database.select(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "providerId", providerId)
                .stream().map(PgGatewayModelStore::toModel).toList();
    }

    @Override
    public List<GatewayModelConfig> listByModelId(String modelId) {
        return database.select(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "modelId", modelId)
                .stream().map(PgGatewayModelStore::toModel).toList();
    }

    @Override
    public void insert(GatewayModelConfig model) {
        store().insert(model);
    }

    @Override
    public void replace(GatewayModelConfig model) {
        store().replace(model);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }

    @Override
    public void deleteByProviderId(String providerId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ?", "providerId", providerId);
    }

    @Override
    public void clearDefault(String providerId) {
        database.execute("UPDATE " + TABLE + " SET data = data || ?::jsonb "
            + "WHERE data ->> ? = ? AND (data ->> ?)::boolean = true", "{\"isDefault\":false}", "providerId", providerId, "isDefault");
    }

    @Override
    public void clearMessagesFallbackExcept(String keepId) {
        database.execute("UPDATE " + TABLE + " SET data = data || ?::jsonb "
            + "WHERE (data ->> ?)::boolean = true AND id <> ?", "{\"messagesFallback\":false}", "messagesFallback", keepId);
    }

    @Override
    public boolean existsEnabledModel(String modelId) {
        return !database.select(SELECT + " WHERE data ->> ? = ? AND (data ->> ?)::boolean IS NOT FALSE LIMIT 1",
                PgDocumentRow.class, "modelId", modelId, "enabled").isEmpty();
    }

    @Override
    public boolean existsEnabledModelWithEndpoint(String modelId, String endpointType) {
        return !database.select(SELECT + " WHERE data ->> ? = ? AND (data ->> ?)::boolean IS NOT FALSE "
                        + "AND data -> ? @> ?::jsonb LIMIT 1",
                PgDocumentRow.class, "modelId", modelId, "enabled", "endpointTypes", "[\"" + endpointType + "\"]").isEmpty();
    }
}
