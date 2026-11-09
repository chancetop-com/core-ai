package ai.core.server.settings;

import ai.core.server.domain.SystemSettings;
import ai.core.server.pg.PgDocumentStore;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.Optional;

/**
 * @author stephen
 */
public class PgSystemSettingsStore implements SystemSettingsStore {
    @Inject
    Database database;

    private PgDocumentStore<SystemSettings> store() {
        return new PgDocumentStore<>(database, "app.system_settings", SystemSettings.class, entity -> entity.id);
    }

    @Override
    public Optional<SystemSettings> get(String id) {
        return store().get(id);
    }

    @Override
    public void insert(SystemSettings entity) {
        store().insert(entity);
    }

    @Override
    public void replace(SystemSettings entity) {
        store().replace(entity);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}
