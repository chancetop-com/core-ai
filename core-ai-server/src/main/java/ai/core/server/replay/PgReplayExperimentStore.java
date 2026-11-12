package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayExperimentOrigin;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgReplayExperimentStore implements ReplayExperimentStore {
    private static final String TABLE = "app.replay_experiments";
    private static final String SELECT = "SELECT id, user_id, origin, trace_id, span_id, agent_id, agent_name, session_id, "
        + "trace_source, span_name, original_model, run_count, note, draft_request, created_at, updated_at, "
        + "data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, origin, trace_id, span_id, agent_id, "
        + "agent_name, session_id, trace_source, span_name, original_model, run_count, note, draft_request, created_at, "
        + "updated_at, data) ";

    private static ReplayExperiment toExperiment(PgReplayExperimentRow row) {
        var experiment = JsonUtil.fromJson(ReplayExperiment.class, row.data);
        experiment.id = row.id;
        experiment.userId = row.userId;
        experiment.origin = origin(row.origin);
        experiment.traceId = row.traceId;
        experiment.spanId = row.spanId;
        experiment.agentId = row.agentId;
        experiment.agentName = row.agentName;
        experiment.sessionId = row.sessionId;
        experiment.traceSource = row.traceSource;
        experiment.spanName = row.spanName;
        experiment.originalModel = row.originalModel;
        experiment.runCount = row.runCount;
        experiment.note = row.note;
        experiment.draftRequest = row.draftRequest;
        experiment.createdAt = row.createdAt;
        experiment.updatedAt = row.updatedAt;
        return experiment;
    }

    private static String origin(ReplayExperimentOrigin origin) {
        return origin == null ? null : origin.name().toLowerCase(Locale.ROOT);
    }

    private static ReplayExperimentOrigin origin(String origin) {
        return origin == null ? null : ReplayExperimentOrigin.valueOf(origin.toUpperCase(Locale.ROOT));
    }

    @Inject
    public Database database;

    @Override
    public Optional<ReplayExperiment> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgReplayExperimentRow.class, id)
            .map(PgReplayExperimentStore::toExperiment);
    }

    @Override
    public void insert(ReplayExperiment experiment) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(experiment));
    }

    @Override
    public void replace(ReplayExperiment experiment) {
        database.execute("UPDATE " + TABLE + " SET user_id = ?, origin = ?, trace_id = ?, span_id = ?, agent_id = ?, "
                + "agent_name = ?, session_id = ?, trace_source = ?, span_name = ?, original_model = ?, run_count = ?, "
                + "note = ?, draft_request = ?, created_at = ?, updated_at = ?, data = ?::jsonb WHERE id = ?",
            experiment.userId, origin(experiment.origin), experiment.traceId, experiment.spanId, experiment.agentId,
            experiment.agentName, experiment.sessionId, experiment.traceSource, experiment.spanName,
            experiment.originalModel, experiment.runCount, experiment.note,
            JsonSanitizer.text(experiment.draftRequest), experiment.createdAt, experiment.updatedAt,
            JsonSanitizer.json(JsonUtil.toJson(experiment)), experiment.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public List<ReplayExperiment> find(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin,
                                       int offset, int limit) {
        var where = filter(userId, admin, agentId, origin);
        var sql = new StringBuilder(512).append(SELECT).append(where.sql)
            .append(" ORDER BY created_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?");
        var params = new ArrayList<>(where.params);
        params.add(Math.clamp(limit, 1, MAX_LIST_LIMIT));
        params.add(Math.max(offset, 0));
        return database.select(sql.toString(), PgReplayExperimentRow.class, params.toArray())
            .stream().map(PgReplayExperimentStore::toExperiment).toList();
    }

    @Override
    public long count(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin) {
        var where = filter(userId, admin, agentId, origin);
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + where.sql,
                PgReplayExperimentCountRow.class, where.params.toArray())
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public void incrementRunCount(String id, ZonedDateTime updatedAt) {
        database.execute("UPDATE " + TABLE + " SET run_count = COALESCE(run_count, 0) + 1, updated_at = ? WHERE id = ?",
            updatedAt, id);
    }

    @Override
    public int deleteUntouchedBlankBefore(ZonedDateTime cutoff, String blankDraft) {
        return database.execute("DELETE FROM " + TABLE + " WHERE origin = ? AND run_count = 0 AND note IS NULL "
                + "AND (draft_request IS NULL OR draft_request = ?) AND created_at < ?",
            "blank", blankDraft, cutoff);
    }

    private Where filter(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<>();
        if (!admin) {
            conditions.add("user_id = ?");
            params.add(userId);
        }
        if (agentId != null && !agentId.isBlank()) {
            conditions.add("agent_id = ?");
            params.add(agentId);
        }
        if (origin != null) {
            conditions.add("origin = ?");
            params.add(origin(origin));
        }
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    private Object[] params(ReplayExperiment experiment) {
        return new Object[]{
            experiment.id,
            experiment.userId,
            origin(experiment.origin),
            experiment.traceId,
            experiment.spanId,
            experiment.agentId,
            experiment.agentName,
            experiment.sessionId,
            experiment.traceSource,
            experiment.spanName,
            experiment.originalModel,
            experiment.runCount,
            experiment.note,
            JsonSanitizer.text(experiment.draftRequest),
            experiment.createdAt,
            experiment.updatedAt,
            JsonSanitizer.json(JsonUtil.toJson(experiment))
        };
    }

    private record Where(String sql, List<Object> params) {
    }
}

