package ai.core.server.web.foryou;

import ai.core.server.domain.UserTodo;
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
public class PgUserTodoStore implements UserTodoStore {
    private static final String TABLE = "app.user_todos";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    public Database database;

    private PgDocumentStore<UserTodo> store() {
        return new PgDocumentStore<>(database, TABLE, UserTodo.class, todo -> todo.id);
    }

    @Override
    public Optional<UserTodo> get(String id) {
        return store().get(id);
    }

    @Override
    public List<UserTodo> list(String userId) {
        return listLimited(userId, false, Integer.MAX_VALUE);
    }

    @Override
    public List<UserTodo> active(String userId) {
        return listLimited(userId, true, Integer.MAX_VALUE);
    }

    private List<UserTodo> listLimited(String userId, boolean activeOnly, int limit) {
        var sql = new StringBuilder(SELECT.length() + 128).append(SELECT).append(" WHERE data ->> ? = ?");
        var params = new java.util.ArrayList<>();
        params.add("userId");
        params.add(userId);
        if (activeOnly) {
            sql.append(" AND (data ->> ?)::boolean = false");
            params.add("completed");
        }
        sql.append(" ORDER BY data ->> ? ASC LIMIT ?");
        params.add("createdAt");
        params.add(limit);
        return database.select(sql.toString(), PgDocumentRow.class, params.toArray())
            .stream().map(row -> JsonUtil.fromJson(UserTodo.class, row.data)).toList();
    }

    @Override
    public long count(String userId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ?",
            PgForYouCountRow.class, "userId", userId).map(row -> row.total).orElse(0L);
    }

    @Override
    public long activeCount(String userId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ? "
            + "AND (data ->> ?)::boolean = false", PgForYouCountRow.class, "userId", userId, "completed")
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public void insert(UserTodo todo) {
        store().insert(todo);
    }

    @Override
    public boolean insertIfAbsent(UserTodo todo) {
        return store().insertIfAbsent(todo);
    }

    @Override
    public void replace(UserTodo todo) {
        store().replace(todo);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}



