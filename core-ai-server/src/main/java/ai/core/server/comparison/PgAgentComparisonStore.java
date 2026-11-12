package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgAgentComparisonStore implements AgentComparisonStore {
    private static final String TABLE = "app.agent_comparisons";
    private static final String SELECT = "SELECT id, agent_id, agent_name, status, created_by, created_at, "
        + "started_at, ended_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, agent_id, agent_name, status, created_by, "
        + "created_at, started_at, ended_at, data) ";

    private static AgentComparison toComparison(PgAgentComparisonRow row) {
        var comparison = JsonUtil.fromJson(AgentComparison.class, row.data);
        comparison.id = row.id;
        comparison.agentId = row.agentId;
        comparison.agentName = row.agentName;
        comparison.status = row.status != null ? ai.core.server.comparison.domain.ComparisonStatus.valueOf(row.status) : null;
        comparison.createdBy = row.createdBy;
        comparison.createdAt = row.createdAt;
        comparison.startedAt = row.startedAt;
        comparison.endedAt = row.endedAt;
        return comparison;
    }

    @Inject
    public Database database;

    @Override
    public Optional<AgentComparison> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgAgentComparisonRow.class, id)
            .map(PgAgentComparisonStore::toComparison);
    }

    @Override
    public void insert(AgentComparison comparison) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(comparison));
    }

    @Override
    public void replace(AgentComparison comparison) {
        database.execute("UPDATE " + TABLE + " SET agent_id = ?, agent_name = ?, status = ?, created_by = ?, "
                + "created_at = ?, started_at = ?, ended_at = ?, data = ?::jsonb WHERE id = ?",
            comparison.agentId, comparison.agentName,
            comparison.status != null ? comparison.status.name() : null, comparison.createdBy,
            comparison.createdAt, comparison.startedAt, comparison.endedAt,
            JsonSanitizer.json(JsonUtil.toJson(comparison)), comparison.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public List<AgentComparison> find(AgentComparisonQuery request) {
        var sql = new StringBuilder(512).append(SELECT);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        sql.append(" ORDER BY created_at DESC NULLS LAST, id DESC LIMIT ?");
        params.add(request.limit);
        return database.select(sql.toString(), PgAgentComparisonRow.class, params.toArray())
            .stream().map(PgAgentComparisonStore::toComparison).toList();
    }

    @Override
    public Optional<AgentComparison> findOne(AgentComparisonQuery request) {
        request.limit = 1;
        var rows = find(request);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private void conditions(AgentComparisonQuery request, StringBuilder sql, List<Object> params) {
        var where = new ArrayList<String>();
        if (request.agentId != null) {
            where.add("agent_id = ?");
            params.add(request.agentId);
        }
        if (request.createdBy != null) {
            where.add("created_by = ?");
            params.add(request.createdBy);
        }
        if (request.participantUserId != null) {
            where.add("data -> ? @> ?::jsonb");
            params.add("participantUserIds");
            params.add("[\"" + request.participantUserId.replace("\"", "\\\"") + "\"]");
        }
        if (request.status != null) {
            where.add("status = ?");
            params.add(request.status.name());
        }
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
    }

    private Object[] params(AgentComparison comparison) {
        return new Object[]{
            comparison.id,
            comparison.agentId,
            comparison.agentName,
            comparison.status != null ? comparison.status.name() : null,
            comparison.createdBy,
            comparison.createdAt,
            comparison.startedAt,
            comparison.endedAt,
            JsonSanitizer.json(JsonUtil.toJson(comparison))
        };
    }
}


