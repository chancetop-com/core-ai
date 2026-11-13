package ai.core.server.trace;

import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceFacetRow;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class PgTraceStore implements TraceStore {
    private static final String TABLE = "app.traces";
    private static final String COLUMNS = "id, trace_id, name, model, type, source, agent_name, agent_id, session_id, "
        + "user_id, status, error_message, input, output, metadata::text AS metadata, input_tokens, output_tokens, "
        + "total_tokens, cached_tokens, cost_usd, duration_ms, started_at, completed_at, created_at, updated_at";
    private static final String SELECT = "SELECT " + COLUMNS + " FROM " + TABLE;
    private static final String INSERT_COLUMNS = " (id, trace_id, name, model, type, source, agent_name, agent_id, "
        + "session_id, user_id, status, error_message, input, output, metadata, input_tokens, output_tokens, "
        + "total_tokens, cached_tokens, cost_usd, duration_ms, started_at, completed_at, created_at, updated_at) ";
    private static final String INSERT_VALUES = " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, "
        + "?, ?, ?, ?, ?, ?)";
    private static final String INSERT = "INSERT INTO " + TABLE + INSERT_COLUMNS + INSERT_VALUES;
    private static final String INSERT_IF_ABSENT = INSERT + " ON CONFLICT DO NOTHING";
    private static final int DELETE_BATCH_SIZE = 500;

    private static Trace toTrace(PgTraceRow row) {
        var trace = new Trace();
        trace.id = row.id;
        trace.traceId = row.traceId;
        trace.name = row.name;
        trace.model = row.model;
        trace.type = row.type;
        trace.source = row.source;
        trace.agentName = row.agentName;
        trace.agentId = row.agentId;
        trace.sessionId = row.sessionId;
        trace.userId = row.userId;
        trace.status = row.status != null ? TraceStatus.valueOf(row.status) : null;
        trace.errorMessage = row.errorMessage;
        trace.input = row.input;
        trace.output = row.output;
        trace.metadata = row.metadata != null ? JsonUtil.fromJson(METADATA_TYPE, row.metadata) : null;
        trace.inputTokens = row.inputTokens;
        trace.outputTokens = row.outputTokens;
        trace.totalTokens = row.totalTokens;
        trace.cachedTokens = row.cachedTokens;
        trace.costUsd = row.costUsd;
        trace.durationMs = row.durationMs;
        trace.startedAt = row.startedAt;
        trace.completedAt = row.completedAt;
        trace.createdAt = row.createdAt;
        trace.updatedAt = row.updatedAt;
        return trace;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String status(TraceStatus status) {
        return status == null ? null : status.name();
    }

    private static Object[] params(Trace trace) {
        return new Object[]{
            trace.id, trace.traceId, trace.name, trace.model, trace.type, trace.source, trace.agentName, trace.agentId,
            trace.sessionId, trace.userId, status(trace.status), JsonSanitizer.text(trace.errorMessage),
            JsonSanitizer.text(trace.input), JsonSanitizer.text(trace.output),
            trace.metadata != null ? JsonUtil.toJson(trace.metadata) : null,
            trace.inputTokens, trace.outputTokens, trace.totalTokens, trace.cachedTokens, trace.costUsd,
            trace.durationMs, trace.startedAt, trace.completedAt, trace.createdAt, trace.updatedAt
        };
    }

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() { };

    @Inject
    Database database;
    @Inject
    PgTraceAnalytics analytics;

    @Override
    public void insert(Trace trace) {
        database.execute(INSERT, params(trace));
    }

    @Override
    public boolean insertIfAbsent(Trace trace) {
        return database.execute(INSERT_IF_ABSENT, params(trace)) > 0;
    }

    @Override
    public boolean existsByTraceId(String traceId) {
        return database.selectOne("SELECT id FROM " + TABLE + " WHERE trace_id = ? LIMIT 1", PgTraceIdRow.class, traceId)
            .isPresent();
    }

    @Override
    public Trace findByTraceId(String traceId) {
        return database.selectOne(SELECT + " WHERE trace_id = ? LIMIT 1", PgTraceRow.class, traceId)
            .map(PgTraceStore::toTrace).orElse(null);
    }

    @Override
    public void update(TraceUpdate update) {
        if (update.setOnlyWhenEmpty != null) {
            updateGuarded(update);
            return;
        }

        var sets = new ArrayList<String>();
        var params = new ArrayList<Object>();
        if (update.status != null) set(sets, params, "status", status(update.status));
        if (update.clearErrorMessage) sets.add("error_message = NULL");
        if (update.errorMessage != null) set(sets, params, "error_message", JsonSanitizer.text(update.errorMessage));
        if (update.input != null) set(sets, params, "input", JsonSanitizer.text(update.input));
        if (update.output != null) set(sets, params, "output", JsonSanitizer.text(update.output));
        if (update.durationMs != null) set(sets, params, "duration_ms", update.durationMs);
        if (update.completedAt != null) set(sets, params, "completed_at", update.completedAt);
        if (update.name != null || update.writeIdentityNulls) set(sets, params, "name", update.name);
        if (update.sessionId != null || update.writeIdentityNulls) set(sets, params, "session_id", update.sessionId);
        if (update.userId != null || update.writeIdentityNulls) set(sets, params, "user_id", update.userId);
        if (update.source != null || update.writeIdentityNulls) set(sets, params, "source", update.source);
        if (update.type != null || update.writeIdentityNulls) set(sets, params, "type", update.type);
        if (update.agentName != null || update.writeIdentityNulls) set(sets, params, "agent_name", update.agentName);
        if (update.agentId != null || update.writeIdentityNulls) set(sets, params, "agent_id", update.agentId);
        if (update.model != null) set(sets, params, "model", update.model);
        if (update.metadata != null) {
            sets.add("metadata = ?::jsonb");
            params.add(JsonUtil.toJson(update.metadata));
        }
        increment(sets, params, "input_tokens", update.inputTokensDelta);
        increment(sets, params, "output_tokens", update.outputTokensDelta);
        increment(sets, params, "total_tokens", update.totalTokensDelta);
        increment(sets, params, "cached_tokens", update.cachedTokensDelta);
        increment(sets, params, "cost_usd", update.costUsdDelta);
        if (update.updatedAt != null) set(sets, params, "updated_at", update.updatedAt);
        if (sets.isEmpty()) return;

        var where = new StringBuilder("trace_id = ?");
        params.add(update.traceId);
        if (update.requireStatus != null) {
            where.append(" AND status = ?");
            params.add(status(update.requireStatus));
        }
        database.execute("UPDATE " + TABLE + " SET " + String.join(", ", sets) + " WHERE " + where, params.toArray());
    }

    private void updateGuarded(TraceUpdate update) {
        var column = column(update.setOnlyWhenEmpty);
        var sets = new ArrayList<String>();
        var params = new ArrayList<Object>();
        set(sets, params, column, update.valueWhenEmpty);
        if (update.updatedAt != null) set(sets, params, "updated_at", update.updatedAt);

        var where = new StringBuilder("trace_id = ? AND (" + column + " IS NULL OR length(" + column + ") = 0");
        params.add(update.traceId);
        if (update.setOnlyWhenEmpty == TraceUpdate.EmptyGuard.NAME && update.namePlaceholders != null
            && !update.namePlaceholders.isEmpty()) {
            where.append(" OR ").append(column).append(" IN (").append(placeholders(update.namePlaceholders.size())).append(')');
            params.addAll(update.namePlaceholders);
        }
        where.append(')');
        database.execute("UPDATE " + TABLE + " SET " + String.join(", ", sets) + " WHERE " + where, params.toArray());
    }

    private void set(List<String> sets, List<Object> params, String column, Object value) {
        sets.add(column + " = ?");
        params.add(value);
    }

    private void increment(List<String> sets, List<Object> params, String column, Number delta) {
        if (delta == null || delta.doubleValue() == 0) return;
        sets.add(column + " = COALESCE(" + column + ", 0) + ?");
        params.add(delta);
    }

    private String column(TraceUpdate.EmptyGuard guard) {
        return switch (guard) {
            case AGENT_NAME -> "agent_name";
            case AGENT_ID -> "agent_id";
            case MODEL -> "model";
            case NAME -> "name";
        };
    }

    @Override
    public Trace get(String id, String traceId) {
        if (id != null) {
            var trace = database.selectOne(SELECT + " WHERE id = ? LIMIT 1", PgTraceRow.class, id)
                .map(PgTraceStore::toTrace).orElse(null);
            if (trace != null) return trace;
        }
        return database.selectOne(SELECT + " WHERE trace_id = ? LIMIT 1", PgTraceRow.class, traceId)
            .map(PgTraceStore::toTrace).orElse(null);
    }

    @Override
    public List<Trace> list(TraceQuery criteria) {
        var filter = PgTraceFilter.of(criteria);
        var sql = new StringBuilder(SELECT).append(filter.where())
            .append(criteria.ascending ? " ORDER BY created_at ASC, id ASC" : " ORDER BY created_at DESC, id DESC");
        var params = new ArrayList<>(filter.paramList());
        if (criteria.limit != null) {
            sql.append(" LIMIT ?");
            params.add(criteria.limit);
        }
        if (criteria.skip != null) {
            sql.append(" OFFSET ?");
            params.add(criteria.skip);
        }
        return database.select(sql.toString(), PgTraceRow.class, params.toArray())
            .stream().map(PgTraceStore::toTrace).toList();
    }

    @Override
    public long count(TraceQuery criteria) {
        var filter = PgTraceFilter.of(criteria);
        var row = database.selectOne("SELECT count(*) AS count FROM " + TABLE + filter.where(),
            PgTraceCountRow.class, filter.params()).orElse(null);
        return row == null || row.count == null ? 0 : row.count;
    }

    @Override
    public List<TraceFacetRow> facets(TraceQuery criteria, TraceFacet facet, int limit) {
        if (facet == null) return List.of();
        var filter = PgTraceFilter.of(criteria);
        filter.add(facet.field + " IS NOT NULL");
        filter.add("length(" + facet.field + ") > 0");
        var sql = "SELECT " + facet.field + " AS value, count(*) AS count FROM " + TABLE + filter.where()
            + " GROUP BY " + facet.field + " ORDER BY count DESC LIMIT ?";
        var params = new ArrayList<>(filter.paramList());
        params.add(limit);
        return database.select(sql, PgTraceFacetRow.class, params.toArray()).stream()
            .map(row -> {
                var value = new TraceFacetRow();
                value.value = row.value;
                value.count = row.count;
                return value;
            })
            .toList();
    }

    @Override
    public List<String> traceIds(TraceQuery criteria, int limit) {
        var filter = PgTraceFilter.of(criteria);
        var params = new ArrayList<>(filter.paramList());
        params.add(limit);
        return database.select("SELECT trace_id FROM " + TABLE + filter.where()
                + " ORDER BY created_at DESC LIMIT ?", PgTraceTraceIdRow.class, params.toArray())
            .stream().map(row -> row.traceId).filter(id -> id != null && !id.isEmpty()).toList();
    }

    @Override
    public long countStartedBefore(ZonedDateTime cutoff) {
        var row = database.selectOne("SELECT count(*) AS count FROM " + TABLE + " WHERE started_at < ?",
            PgTraceCountRow.class, cutoff).orElse(null);
        return row == null || row.count == null ? 0 : row.count;
    }

    @Override
    public long deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        long deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            var batch = ids.subList(i, Math.min(i + DELETE_BATCH_SIZE, ids.size()));
            deleted += database.execute("DELETE FROM " + TABLE + " WHERE id IN (" + placeholders(batch.size()) + ")",
                batch.toArray());
        }
        return deleted;
    }

    @Override
    public void backfillIdentity(String traceId, Map<TraceIdentityField, String> values) {
        if (values == null || values.isEmpty()) return;
        var missing = new ArrayList<String>();
        var sets = new ArrayList<String>();
        var params = new ArrayList<Object>();
        for (var entry : values.entrySet()) {
            var column = identityColumn(entry.getKey());
            missing.add("(" + column + " IS NULL OR length(" + column + ") = 0)");
            sets.add(column + " = ?");
            params.add(entry.getValue());
        }
        sets.add("updated_at = ?");
        params.add(ZonedDateTime.now());
        params.add(traceId);
        database.execute("UPDATE " + TABLE + " SET " + String.join(", ", sets)
            + " WHERE trace_id = ? AND (" + String.join(" OR ", missing) + ")", params.toArray());
    }

    private String identityColumn(TraceIdentityField field) {
        return switch (field) {
            case USER_ID -> "user_id";
            case SESSION_ID -> "session_id";
            case AGENT_ID -> "agent_id";
            case AGENT_NAME -> "agent_name";
        };
    }

    // === analytics, delegated to keep this file small ===

    @Override
    public List<TraceAggregateRow> aggregate(TraceAggregation aggregation) {
        return analytics.aggregate(aggregation);
    }

    @Override
    public List<TraceDailyRollupRow> dailyRollup(ZonedDateTime from, ZonedDateTime to) {
        return analytics.dailyRollup(from, to);
    }

    @Override
    public List<TraceDailyRollupRow> analyticsRollup(ZonedDateTime from, ZonedDateTime to,
                                                     Map<String, String> modelToProvider,
                                                     Map<String, String> providerIdToName) {
        return analytics.analyticsRollup(from, to, modelToProvider, providerIdToName);
    }

    @Override
    public List<TraceStatsRow> realtimeStats(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension,
                                             Map<String, String> modelToProvider,
                                             Map<String, String> providerIdToName) {
        return analytics.realtimeStats(from, to, dimension, modelToProvider, providerIdToName);
    }

    @Override
    public List<TraceTrendRow> realtimeTrend(ZonedDateTime from, ZonedDateTime to,
                                             Map<String, String> modelToProvider,
                                             Map<String, String> providerIdToName) {
        return analytics.realtimeTrend(from, to, modelToProvider, providerIdToName);
    }

    @Override
    public List<TraceDimensionTrendRow> realtimeDimensionTrend(ZonedDateTime from, ZonedDateTime to,
                                                               TraceDimension dimension, List<String> keys,
                                                               Map<String, String> modelToProvider,
                                                               Map<String, String> providerIdToName) {
        return analytics.realtimeDimensionTrend(from, to, dimension, keys, modelToProvider, providerIdToName);
    }
}
