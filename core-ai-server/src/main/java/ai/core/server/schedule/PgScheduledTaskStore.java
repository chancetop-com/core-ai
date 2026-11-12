package ai.core.server.schedule;

import ai.core.schedule.CronExpression;
import ai.core.schedule.ScheduledTask;
import ai.core.server.domain.SessionSchedule;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class PgScheduledTaskStore implements SessionScheduleStore {
    private static final String TABLE = "app.session_schedules";
    private static final String SELECT = "SELECT id, session_id, user_id, enabled, next_run_at, created_at, updated_at, "
        + "data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, session_id, user_id, enabled, next_run_at, "
        + "created_at, updated_at, data) ";

    private static SessionSchedule fromRow(PgSessionScheduleRow row) {
        var entity = JsonUtil.fromJson(SessionSchedule.class, row.data);
        entity.id = row.id;
        entity.sessionId = row.sessionId;
        entity.userId = row.userId;
        entity.enabled = row.enabled;
        entity.nextRunAt = row.nextRunAt;
        entity.createdAt = row.createdAt;
        entity.updatedAt = row.updatedAt;
        return entity;
    }

    private static ScheduledTask toTask(SessionSchedule entity) {
        var task = new ScheduledTask();
        task.id = entity.id;
        task.sessionId = entity.sessionId;
        task.userId = entity.userId;
        task.name = entity.name;
        task.cronExpression = entity.cronExpression;
        task.timezone = entity.timezone;
        task.input = entity.input;
        task.enabled = entity.enabled;
        task.nextRunAt = entity.nextRunAt;
        task.createdAt = entity.createdAt;
        task.updatedAt = entity.updatedAt;
        return task;
    }

    @Inject
    public Database database;

    @Override
    public ScheduledTask create(ScheduledTask task) {
        var entity = fromTask(task);
        var now = ZonedDateTime.now();
        entity.nextRunAt = new CronExpression(entity.cronExpression).nextAfter(now, ZoneId.of(entity.timezone));
        entity.createdAt = now;
        entity.updatedAt = now;
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(entity));
        return toTask(entity);
    }

    @Override
    public List<ScheduledTask> list(String sessionId) {
        return database.select(SELECT + " WHERE session_id = ? ORDER BY created_at DESC, id ASC",
                PgSessionScheduleRow.class, sessionId)
            .stream().map(PgScheduledTaskStore::fromRow).map(PgScheduledTaskStore::toTask).toList();
    }

    @Override
    public List<ScheduledTask> listPage(int offset, int limit) {
        return database.select(SELECT + " ORDER BY created_at DESC, id ASC LIMIT ? OFFSET ?",
                PgSessionScheduleRow.class, limit, offset)
            .stream().map(PgScheduledTaskStore::fromRow).map(PgScheduledTaskStore::toTask).toList();
    }

    @Override
    public long count() {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE, PgSessionScheduleCountRow.class)
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public ScheduledTask get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgSessionScheduleRow.class, id)
            .map(PgScheduledTaskStore::fromRow).map(PgScheduledTaskStore::toTask).orElse(null);
    }

    @Override
    public boolean delete(String id) {
        return database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id) > 0;
    }

    @Override
    public List<ScheduledTask> findDue(ZonedDateTime now) {
        return database.select(SELECT + " WHERE enabled = true AND next_run_at <= ? ORDER BY next_run_at, id",
                PgSessionScheduleRow.class, now)
            .stream().map(PgScheduledTaskStore::fromRow).map(PgScheduledTaskStore::toTask).toList();
    }

    @Override
    public boolean claim(String id, ZonedDateTime expectedNextRunAt, ZonedDateTime newNextRunAt) {
        return database.execute("UPDATE " + TABLE + " SET next_run_at = ? WHERE id = ? AND next_run_at = ?",
            newNextRunAt, id, expectedNextRunAt) > 0;
    }

    @Override
    public ScheduledTask updateEnabled(String id, boolean enabled) {
        var task = get(id);
        if (task == null) return null;
        task.enabled = enabled;
        task.updatedAt = ZonedDateTime.now();
        database.execute("UPDATE " + TABLE + " SET enabled = ?, updated_at = ?, data = ?::jsonb WHERE id = ?",
            task.enabled, task.updatedAt, JsonSanitizer.json(JsonUtil.toJson(fromTask(task))), task.id);
        return task;
    }

    @Override
    public boolean insertIfAbsent(ScheduledTask task) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            params(task)) > 0;
    }

    private SessionSchedule fromTask(ScheduledTask task) {
        var entity = new SessionSchedule();
        entity.id = task.id;
        entity.sessionId = task.sessionId;
        entity.userId = task.userId;
        entity.name = task.name;
        entity.cronExpression = task.cronExpression;
        entity.timezone = task.timezone;
        entity.input = task.input;
        entity.enabled = task.enabled;
        entity.nextRunAt = task.nextRunAt;
        entity.createdAt = task.createdAt;
        entity.updatedAt = task.updatedAt;
        return entity;
    }

    private Object[] params(ScheduledTask task) {
        return params(fromTask(task));
    }

    private Object[] params(SessionSchedule entity) {
        return new Object[]{
            entity.id,
            entity.sessionId,
            entity.userId,
            entity.enabled,
            entity.nextRunAt,
            entity.createdAt,
            entity.updatedAt,
            JsonSanitizer.json(JsonUtil.toJson(entity))
        };
    }
}


