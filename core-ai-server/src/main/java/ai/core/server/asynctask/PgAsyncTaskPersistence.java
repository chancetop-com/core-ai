package ai.core.server.asynctask;

import ai.core.persistence.PersistenceProvider;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgAsyncTaskPersistence implements PersistenceProvider {
    private static final String TABLE = "app.async_tool_tasks";

    @Inject
    public Database database;

    @Override
    public void save(String id, String context) {
        database.execute("INSERT INTO " + TABLE + " (id, data, updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = EXCLUDED.updated_at",
            id, context, ZonedDateTime.now());
    }

    @Override
    public void clear() {
        database.execute("DELETE FROM " + TABLE);
    }

    @Override
    public void delete(List<String> ids) {
        if (ids.isEmpty()) return;
        database.execute("DELETE FROM " + TABLE + " WHERE id IN (" + placeholders(ids.size()) + ")", ids.toArray());
    }

    @Override
    public Optional<String> load(String id) {
        return database.selectOne("SELECT data FROM " + TABLE + " WHERE id = ?", PgAsyncTaskRow.class, id)
            .map(row -> row.data);
    }

    @Override
    public List<String> listIds(String prefix) {
        return database.select("SELECT id FROM " + TABLE + " WHERE id LIKE ? ORDER BY id",
                PgAsyncTaskRow.class, prefix + "%")
            .stream().map(row -> row.id).toList();
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
