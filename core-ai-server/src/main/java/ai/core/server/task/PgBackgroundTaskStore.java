package ai.core.server.task;

import ai.core.server.domain.BackgroundTask;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgBackgroundTaskStore implements BackgroundTaskStore {
    private static final String TABLE = "app.background_tasks";
    private static final String SELECT = "SELECT id, type, status, status_text, claimed_by, started_at, completed_at, "
        + "retry_count, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, type, status, status_text, claimed_by, "
        + "started_at, completed_at, retry_count, data) ";

    private static BackgroundTask toTask(PgBackgroundTaskRow row) {
        var task = JsonUtil.fromJson(BackgroundTask.class, row.data);
        task.id = row.id;
        task.type = row.type;
        task.status = row.status != null ? TaskStatus.valueOf(row.status) : null;
        task.statusText = row.statusText;
        task.claimedBy = row.claimedBy;
        task.startedAt = row.startedAt;
        task.completedAt = row.completedAt;
        task.retryCount = row.retryCount;
        return task;
    }

    @Inject
    public Database database;

    @Override
    public Optional<BackgroundTask> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgBackgroundTaskRow.class, id)
            .map(PgBackgroundTaskStore::toTask);
    }

    @Override
    public List<BackgroundTask> list(String type, int limit) {
        var sql = new StringBuilder(512).append(SELECT);
        var params = new java.util.ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql.append(" WHERE type = ?");
            params.add(type);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(Math.min(limit, 100));
        return database.select(sql.toString(), PgBackgroundTaskRow.class, params.toArray())
            .stream().map(PgBackgroundTaskStore::toTask).toList();
    }

    @Override
    public boolean insertIfAbsent(BackgroundTask task) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            params(task)) > 0;
    }

    @Override
    public boolean retryClaim(String id, String claimedBy, ZonedDateTime startedAt) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, claimed_by = ?, started_at = ?, "
                + "retry_count = COALESCE(retry_count, 0) + 1 WHERE id = ? AND status = ?",
            TaskStatus.RUNNING.name(), claimedBy, startedAt, id, TaskStatus.FAILED.name()) > 0;
    }

    @Override
    public void replace(BackgroundTask task) {
        database.execute("UPDATE " + TABLE + " SET type = ?, status = ?, status_text = ?, claimed_by = ?, "
                + "started_at = ?, completed_at = ?, retry_count = ?, data = ?::jsonb WHERE id = ?",
            task.type, task.status != null ? task.status.name() : null, task.statusText, task.claimedBy,
            task.startedAt, task.completedAt, task.retryCount, JsonUtil.toJson(task), task.id);
    }

    private Object[] params(BackgroundTask task) {
        return new Object[]{
            task.id,
            task.type,
            task.status != null ? task.status.name() : null,
            task.statusText,
            task.claimedBy,
            task.startedAt,
            task.completedAt,
            task.retryCount,
            JsonUtil.toJson(task)
        };
    }
}


