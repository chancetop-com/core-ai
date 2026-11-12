package ai.core.server.schedule;

import ai.core.server.domain.AgentSchedule;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgAgentScheduleStore implements AgentScheduleStore {
    private static final String TABLE = "app.agent_schedules";
    private static final String SELECT = "SELECT id, agent_id, user_id, enabled, next_run_at, created_at, updated_at, "
        + "data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, agent_id, user_id, enabled, next_run_at, "
        + "created_at, updated_at, data) ";

    private static AgentSchedule toSchedule(PgAgentScheduleRow row) {
        var schedule = JsonUtil.fromJson(AgentSchedule.class, row.data);
        schedule.id = row.id;
        schedule.agentId = row.agentId;
        schedule.userId = row.userId;
        schedule.enabled = row.enabled;
        schedule.nextRunAt = row.nextRunAt;
        schedule.createdAt = row.createdAt;
        schedule.updatedAt = row.updatedAt;
        return schedule;
    }

    @Inject
    public Database database;

    @Override
    public Optional<AgentSchedule> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgAgentScheduleRow.class, id)
            .map(PgAgentScheduleStore::toSchedule);
    }

    @Override
    public List<AgentSchedule> list() {
        return database.select(SELECT + " ORDER BY created_at DESC, id ASC", PgAgentScheduleRow.class)
            .stream().map(PgAgentScheduleStore::toSchedule).toList();
    }

    @Override
    public List<AgentSchedule> listByAgentId(String agentId) {
        return database.select(SELECT + " WHERE agent_id = ? ORDER BY created_at DESC, id ASC",
                PgAgentScheduleRow.class, agentId)
            .stream().map(PgAgentScheduleStore::toSchedule).toList();
    }

    @Override
    public List<AgentSchedule> listDue(ZonedDateTime now) {
        return database.select(SELECT + " WHERE enabled = true AND next_run_at <= ? ORDER BY next_run_at, id",
                PgAgentScheduleRow.class, now)
            .stream().map(PgAgentScheduleStore::toSchedule).toList();
    }

    @Override
    public void insert(AgentSchedule schedule) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(schedule));
    }

    @Override
    public boolean insertIfAbsent(AgentSchedule schedule) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            params(schedule)) > 0;
    }

    @Override
    public void replace(AgentSchedule schedule) {
        database.execute("UPDATE " + TABLE + " SET agent_id = ?, user_id = ?, enabled = ?, next_run_at = ?, "
                + "created_at = ?, updated_at = ?, data = ?::jsonb WHERE id = ?",
            schedule.agentId, schedule.userId, schedule.enabled, schedule.nextRunAt, schedule.createdAt,
            schedule.updatedAt, JsonSanitizer.json(JsonUtil.toJson(schedule)), schedule.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public boolean claim(String id, ZonedDateTime now, ZonedDateTime nextRunAt) {
        return database.execute("UPDATE " + TABLE + " SET next_run_at = ? "
            + "WHERE id = ? AND enabled = true AND next_run_at <= ?", nextRunAt, id, now) > 0;
    }

    @Override
    public boolean rollback(String id, ZonedDateTime claimedNextRunAt, ZonedDateTime originalNextRunAt) {
        return database.execute("UPDATE " + TABLE + " SET next_run_at = ? WHERE id = ? AND next_run_at = ?",
            originalNextRunAt, id, claimedNextRunAt) > 0;
    }

    private Object[] params(AgentSchedule schedule) {
        return new Object[]{
            schedule.id,
            schedule.agentId,
            schedule.userId,
            schedule.enabled,
            schedule.nextRunAt,
            schedule.createdAt,
            schedule.updatedAt,
            JsonSanitizer.json(JsonUtil.toJson(schedule))
        };
    }
}

