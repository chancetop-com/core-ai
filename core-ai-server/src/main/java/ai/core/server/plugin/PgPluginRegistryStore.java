package ai.core.server.plugin;

import ai.core.server.domain.PluginRegistryEntry;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgPluginRegistryStore implements PluginRegistryStore {
    private static final String TABLE = "app.plugins";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static PluginRegistryEntry toEntry(PgDocumentRow row) {
        return JsonUtil.fromJson(PluginRegistryEntry.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<PluginRegistryEntry> store() {
        return new PgDocumentStore<>(database, TABLE, PluginRegistryEntry.class, entry -> entry.id);
    }

    @Override
    public Optional<PluginRegistryEntry> get(String id) {
        return store().get(id);
    }

    @Override
    public List<PluginRegistryEntry> search(String queryText, int offset, int limit) {
        var params = new ArrayList<Object>();
        var sql = new StringBuilder(SELECT.length() + 128).append(SELECT).append(where(queryText, params))
            .append(" ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(Math.clamp(limit, 1, 100));
        params.add(Math.max(0, offset));
        return database.select(sql.toString(), PgDocumentRow.class, params.toArray())
            .stream().map(PgPluginRegistryStore::toEntry).toList();
    }

    @Override
    public long count(String queryText) {
        var params = new ArrayList<Object>();
        var sql = "SELECT count(*) AS total FROM " + TABLE + where(queryText, params);
        return database.selectOne(sql, PgPluginCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
    }

    @Override
    public void upsert(PluginRegistryEntry entry) {
        database.execute("INSERT INTO " + TABLE + " (id, data, updated_at) VALUES (?, ?::jsonb, now()) "
            + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()",
            entry.id, JsonUtil.toJson(entry));
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }

    // package-private for tests: the params must stay paired with their columns
    String where(String queryText, List<Object> params) {
        if (queryText == null || queryText.isBlank()) return "";
        var like = "%" + queryText.trim() + "%";
        params.add("name");
        params.add(like);
        params.add("description");
        params.add(like);
        params.add("vendor");
        params.add(like);
        params.add(like);
        return " WHERE data ->> ? ILIKE ? OR data ->> ? ILIKE ? OR data ->> ? ILIKE ? OR id ILIKE ?";
    }
}
