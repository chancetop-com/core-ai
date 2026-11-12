package ai.core.server.hub;

import ai.core.server.domain.HubCall;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;

/**
 * @author stephen
 */
public class PgHubCallStore implements HubCallStore {
    private static final String TABLE = "app.hub_calls";
    private static final String SELECT = "SELECT id, kind, user_id, user_type, source, target, ref_id, group_name, "
        + "name, sandbox_id, tool_kind, session_id, args_hash, args_preview, success, is_error, status_code, "
        + "duration_ms, output_bytes, error_message, task_id, context_id, input_tokens, output_tokens, created_at, "
        + "data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, kind, user_id, user_type, source, target, "
        + "ref_id, group_name, name, sandbox_id, tool_kind, session_id, args_hash, args_preview, success, is_error, "
        + "status_code, duration_ms, output_bytes, error_message, task_id, context_id, input_tokens, output_tokens, "
        + "created_at, data) ";

    private static HubCall toCall(PgHubCallRow row) {
        var call = JsonUtil.fromJson(HubCall.class, row.data);
        call.id = row.id;
        call.kind = row.kind;
        call.userId = row.userId;
        call.userType = row.userType;
        call.source = row.source;
        call.target = row.target;
        call.refId = row.refId;
        call.group = row.group;
        call.name = row.name;
        call.sandboxId = row.sandboxId;
        call.toolKind = row.toolKind;
        call.sessionId = row.sessionId;
        call.argsHash = row.argsHash;
        call.argsPreview = row.argsPreview;
        call.success = row.success;
        call.isError = row.isError;
        call.statusCode = row.statusCode;
        call.durationMs = row.durationMs;
        call.outputBytes = row.outputBytes;
        call.errorMessage = row.errorMessage;
        call.taskId = row.taskId;
        call.contextId = row.contextId;
        call.inputTokens = row.inputTokens;
        call.outputTokens = row.outputTokens;
        call.createdAt = row.createdAt;
        return call;
    }

    @Inject
    public Database database;

    @Override
    public void insert(HubCall call) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
            params(call));
    }

    @Override
    public void finish(String callId, long durationMs, Integer outputBytes, boolean success,
                       Integer statusCode, String errorMessage) {
        database.execute("UPDATE " + TABLE + " SET success = ?, is_error = ?, duration_ms = ?, status_code = ?, "
                + "output_bytes = ?, error_message = ? WHERE id = ?",
            success, !success, durationMs, statusCode, outputBytes, errorMessage, callId);
    }

    @Override
    public void attachRun(String callId, String taskId, String contextId, Long inputTokens, Long outputTokens) {
        database.execute("UPDATE " + TABLE + " SET task_id = COALESCE(?, task_id), "
                + "context_id = COALESCE(?, context_id), input_tokens = COALESCE(?, input_tokens), "
                + "output_tokens = COALESCE(?, output_tokens) WHERE id = ?",
            taskId, contextId, inputTokens, outputTokens, callId);
    }

    @Override
    public void attachResult(String callId, String refId, Long inputTokens, Long outputTokens) {
        database.execute("UPDATE " + TABLE + " SET ref_id = COALESCE(?, ref_id), "
                + "input_tokens = COALESCE(?, input_tokens), output_tokens = COALESCE(?, output_tokens) WHERE id = ?",
            refId, inputTokens, outputTokens, callId);
    }

    @Override
    public HubCallPage list(HubCallListFilter filter) {
        var where = new StringBuilder(512).append(" WHERE created_at >= ?");
        var params = new ArrayList<>();
        params.add(filter.startFrom);
        if (filter.startTo != null) {
            where.append(" AND created_at <= ?");
            params.add(filter.startTo);
        }
        if (filter.kind != null && !filter.kind.isBlank()) {
            where.append(" AND kind = ?");
            params.add(filter.kind);
        }
        if (filter.userId != null && !filter.userId.isBlank()) {
            where.append(" AND user_id = ?");
            params.add(filter.userId);
        }
        if (filter.source != null && !filter.source.isBlank()) {
            where.append(" AND source = ?");
            params.add(filter.source);
        }
        if (filter.state != null && !filter.state.isBlank()) {
            where.append(" AND success IS ").append("completed".equals(filter.state) ? "TRUE"
                : "failed".equals(filter.state) ? "FALSE" : "NULL");
        }
        var total = database.selectOne("SELECT count(*) AS total FROM " + TABLE + where,
                PgHubCallCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
        var pageParams = new ArrayList<>(params);
        var sql = new StringBuilder(512).append(SELECT).append(where)
            .append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        pageParams.add(filter.limit);
        pageParams.add(filter.offset);
        return new HubCallPage(total, database.select(sql.toString(), PgHubCallRow.class, pageParams.toArray())
            .stream().map(PgHubCallStore::toCall).toList());
    }

    @Override
    public int deleteOlderThan(ZonedDateTime cutoff) {
        return database.execute("DELETE FROM " + TABLE + " WHERE created_at < ?", cutoff);
    }

    private Object[] params(HubCall call) {
        return new Object[]{
            call.id,
            call.kind,
            call.userId,
            call.userType,
            call.source,
            call.target,
            call.refId,
            call.group,
            call.name,
            call.sandboxId,
            call.toolKind,
            call.sessionId,
            call.argsHash,
            call.argsPreview,
            call.success,
            call.isError,
            call.statusCode,
            call.durationMs,
            call.outputBytes,
            call.errorMessage,
            call.taskId,
            call.contextId,
            call.inputTokens,
            call.outputTokens,
            call.createdAt,
            JsonSanitizer.json(JsonUtil.toJson(call))
        };
    }
}


