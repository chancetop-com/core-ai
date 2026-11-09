package ai.core.server.trigger;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.server.trigger.domain.Trigger;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgTriggerStore implements TriggerStore {
    private static final String TABLE = "app.triggers";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static Trigger toTrigger(PgDocumentRow row) {
        return JsonUtil.fromJson(Trigger.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<Trigger> store() {
        return new PgDocumentStore<>(database, TABLE, Trigger.class, trigger -> trigger.id);
    }

    @Override
    public List<Trigger> list(String type) {
        if (type == null || type.isBlank()) {
            return database.select(SELECT + " ORDER BY updated_at DESC", PgDocumentRow.class)
                    .stream().map(PgTriggerStore::toTrigger).toList();
        }
        return database.select(SELECT + " WHERE data ->> ? = ? ORDER BY updated_at DESC", PgDocumentRow.class, "type", type)
                .stream().map(PgTriggerStore::toTrigger).toList();
    }

    @Override
    public Optional<Trigger> get(String id) {
        return store().get(id);
    }

    @Override
    public void insert(Trigger trigger) {
        store().insert(trigger);
    }

    @Override
    public void replace(Trigger trigger) {
        store().replace(trigger);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}
