package ai.core.server.apimcp.serviceapi.service;

import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
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
public class PgServiceApiStore implements ServiceApiStore {
    private static final String TABLE = "app.service_api";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static ServiceApi toApi(PgDocumentRow row) {
        return JsonUtil.fromJson(ServiceApi.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<ServiceApi> store() {
        return new PgDocumentStore<>(database, TABLE, ServiceApi.class, api -> api.id);
    }

    @Override
    public List<ServiceApi> listAll() {
        return database.select(SELECT + " ORDER BY updated_at DESC", PgDocumentRow.class)
                .stream().map(PgServiceApiStore::toApi).toList();
    }

    @Override
    public Optional<ServiceApi> get(String id) {
        return store().get(id);
    }

    @Override
    public Optional<ServiceApi> getByName(String name) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "name", name)
                .map(PgServiceApiStore::toApi);
    }

    @Override
    public void insert(ServiceApi api) {
        store().insert(api);
    }

    @Override
    public void replace(ServiceApi api) {
        store().replace(api);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}
