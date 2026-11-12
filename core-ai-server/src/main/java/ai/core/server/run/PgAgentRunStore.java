package ai.core.server.run;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgAgentRunStore implements AgentRunStore {
    private static final String TABLE = "app.agent_runs";
    private static final String SELECT = "SELECT id, agent_id, user_id, triggered_by, status, schedule_id, trace_id, "
        + "started_at, queued_at, completed_at, has_artifacts, output, error, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, agent_id, user_id, triggered_by, status, "
        + "schedule_id, trace_id, started_at, queued_at, completed_at, has_artifacts, output, error, data) ";

    private static AgentRun toRun(PgAgentRunRow row) {
        var run = JsonUtil.fromJson(AgentRun.class, row.data);
        run.id = row.id;
        run.agentId = row.agentId;
        run.userId = row.userId;
        run.triggeredBy = row.triggeredBy != null ? ai.core.server.domain.TriggerType.valueOf(row.triggeredBy) : null;
        run.status = row.status != null ? RunStatus.valueOf(row.status) : null;
        run.scheduleId = row.scheduleId;
        run.traceId = row.traceId;
        run.startedAt = row.startedAt;
        run.queuedAt = row.queuedAt;
        run.completedAt = row.completedAt;
        run.output = row.output;
        run.error = row.error;
        return run;
    }

    @Inject
    public Database database;

    @Override
    public Optional<AgentRun> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgAgentRunRow.class, id)
            .map(PgAgentRunStore::toRun);
    }

    @Override
    public void insert(AgentRun run) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(run));
    }

    @Override
    public void replace(AgentRun run) {
        database.execute("UPDATE " + TABLE + " SET agent_id = ?, user_id = ?, triggered_by = ?, status = ?, "
                + "schedule_id = ?, trace_id = ?, started_at = ?, queued_at = ?, completed_at = ?, has_artifacts = ?, "
                + "output = ?, error = ?, data = ?::jsonb WHERE id = ?",
            run.agentId, run.userId, run.triggeredBy != null ? run.triggeredBy.name() : null,
            run.status != null ? run.status.name() : null, run.scheduleId, run.traceId, run.startedAt,
            run.queuedAt, run.completedAt, run.artifacts != null && !run.artifacts.isEmpty(), run.output, run.error,
            JsonUtil.toJson(run), run.id);
    }

    @Override
    public List<AgentRun> find(AgentRunQuery request) {
        var sql = new StringBuilder(512).append(SELECT);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        sql.append(" ORDER BY ").append(sortField(request.sortField))
            .append(request.ascending ? " ASC" : " DESC")
            .append(" NULLS LAST, id ASC LIMIT ? OFFSET ?");
        params.add(request.limit <= 0 ? 1000 : request.limit);
        params.add(Math.max(request.offset, 0));
        return database.select(sql.toString(), PgAgentRunRow.class, params.toArray())
            .stream().map(PgAgentRunStore::toRun).toList();
    }

    @Override
    public long count(AgentRunQuery request) {
        var sql = new StringBuilder("SELECT count(*) AS total FROM ").append(TABLE);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        return database.selectOne(sql.toString(), PgAgentRunCountRow.class, params.toArray())
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public Optional<AgentRun> findOne(AgentRunQuery request) {
        request.limit = 1;
        var runs = find(request);
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.getFirst());
    }

    @Override
    public boolean claimTraceId(String id, String traceId) {
        return database.execute("UPDATE " + TABLE + " SET trace_id = ? WHERE id = ? "
            + "AND (trace_id IS NULL OR trace_id = ?)", traceId, id, "") > 0;
    }

    @Override
    public boolean failIfUnfinished(String id, String error, ZonedDateTime completedAt) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, error = ?, completed_at = ? "
                + "WHERE id = ? AND status IN (?, ?)",
            RunStatus.FAILED.name(), error, completedAt, id, RunStatus.RUNNING.name(), RunStatus.PENDING.name()) > 0;
    }

    @Override
    public boolean reconcileIfUnfinished(String id, boolean completed, String output, String error, ZonedDateTime completedAt) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, output = ?, error = ?, completed_at = ? "
                + "WHERE id = ? AND status IN (?, ?)",
            completed ? RunStatus.COMPLETED.name() : RunStatus.FAILED.name(),
            completed ? output : null, completed ? null : error, completedAt, id,
            RunStatus.RUNNING.name(), RunStatus.PENDING.name()) > 0;
    }

    private void conditions(AgentRunQuery request, StringBuilder sql, List<Object> params) {
        var where = new ArrayList<String>();
        if (request.agentId != null) {
            where.add("agent_id = ?");
            params.add(request.agentId);
        }
        if (request.agentIds != null) {
            if (request.agentIds.isEmpty()) {
                where.add("1 = 0");
            } else {
                where.add("agent_id IN (" + placeholders(request.agentIds.size()) + ")");
                params.addAll(request.agentIds);
            }
        }
        if (request.userId != null) {
            where.add("user_id = ?");
            params.add(request.userId);
        }
        if (request.scheduleId != null) {
            where.add("schedule_id = ?");
            params.add(request.scheduleId);
        }
        if (request.ids != null) {
            if (request.ids.isEmpty()) {
                where.add("1 = 0");
            } else {
                where.add("id IN (" + placeholders(request.ids.size()) + ")");
                params.addAll(request.ids);
            }
        }
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) {
            where.add("id NOT IN (" + placeholders(request.excludeIds.size()) + ")");
            params.addAll(request.excludeIds);
        }
        statusConditions(request, where, params);
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
    }

    private void statusConditions(AgentRunQuery request, List<String> where, List<Object> params) {
        if (request.status != null) {
            where.add("status = ?");
            params.add(request.status.name());
        }
        if (request.statuses != null && !request.statuses.isEmpty()) {
            where.add("status IN (" + placeholders(request.statuses.size()) + ")");
            params.addAll(request.statuses.stream().map(Enum::name).toList());
        }
        var timeField = "completed_at".equals(request.fromField) ? "completed_at" : "started_at";
        if (request.from != null) {
            where.add(timeField + " >= ?");
            params.add(request.from);
        }
        if (request.to != null) {
            where.add(timeField + " < ?");
            params.add(request.to);
        }
        if (request.hasArtifacts) where.add("has_artifacts = true");
    }

    private String sortField(String field) {
        return "completed_at".equals(field) ? "completed_at" : "started_at";
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private Object[] params(AgentRun run) {
        return new Object[]{
            run.id,
            run.agentId,
            run.userId,
            run.triggeredBy != null ? run.triggeredBy.name() : null,
            run.status != null ? run.status.name() : null,
            run.scheduleId,
            run.traceId,
            run.startedAt,
            run.queuedAt,
            run.completedAt,
            run.artifacts != null && !run.artifacts.isEmpty(),
            run.output,
            run.error,
            JsonUtil.toJson(run)
        };
    }
}

