package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
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
public class PgGatewayProviderStore implements GatewayProviderStore {
    private static final String TABLE = "app.gateway_provider";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static GatewayProviderConfig toProvider(PgDocumentRow row) {
        return JsonUtil.fromJson(GatewayProviderConfig.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<GatewayProviderConfig> store() {
        return new PgDocumentStore<>(database, TABLE, GatewayProviderConfig.class, provider -> provider.id);
    }

    @Override
    public List<GatewayProviderConfig> listAll() {
        return database.select(SELECT + " ORDER BY data ->> ?", PgDocumentRow.class, "name")
                .stream().map(PgGatewayProviderStore::toProvider).toList();
    }

    @Override
    public Optional<GatewayProviderConfig> get(String id) {
        return store().get(id);
    }

    @Override
    public void insert(GatewayProviderConfig provider) {
        store().insert(provider);
    }

    @Override
    public void replace(GatewayProviderConfig provider) {
        store().replace(provider);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}
