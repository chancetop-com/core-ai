package ai.core.server.pg;

import ai.core.utils.JsonUtil;
import core.framework.db.Database;

import java.util.Optional;
import java.util.function.Function;

/**
 * JSONB document store for entities whose fields are mostly read/written as a whole. This is not a
 * Mongo compatibility layer: callers still use explicit SQL for filtering, sorting and aggregation;
 * only the row payload is serialized as JSON.
 *
 * @author stephen
 */
public class PgDocumentStore<T> {
    private final Database database;
    private final String table;
    private final Class<T> entityClass;
    private final Function<T, String> id;

    public PgDocumentStore(Database database, String table, Class<T> entityClass, Function<T, String> id) {
        this.database = database;
        this.table = table;
        this.entityClass = entityClass;
        this.id = id;
    }

    public Optional<T> get(String id) {
        return database.selectOne("SELECT data::text AS data FROM " + table + " WHERE id = ?", PgDocumentRow.class, id)
            .map(row -> JsonUtil.fromJson(entityClass, row.data));
    }

    public void insert(T entity) {
        database.execute("INSERT INTO " + table + " (id, data, updated_at) VALUES (?, ?::jsonb, now())",
            id.apply(entity), JsonUtil.toJson(entity));
    }

    public void replace(T entity) {
        database.execute("UPDATE " + table + " SET data = ?::jsonb, updated_at = now() WHERE id = ?",
            JsonUtil.toJson(entity), id.apply(entity));
    }

    public void delete(String id) {
        database.execute("DELETE FROM " + table + " WHERE id = ?", id);
    }
}
