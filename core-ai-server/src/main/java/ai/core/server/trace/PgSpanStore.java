package ai.core.server.trace;

import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class PgSpanStore implements SpanStore {
    private static final String TABLE = "app.spans";
    private static final String COLUMNS = "id, trace_id, user_id, span_id, parent_span_id, name, type, model, status, "
        + "tool_call_id, input_tokens, output_tokens, cached_tokens, cost_usd, cost_source, pricing_model_id, "
        + "input_price_per_1m_tokens, output_price_per_1m_tokens, duration_ms, error_message, attributes::text AS attributes, "
        + "started_at, completed_at, created_at";
    private static final String SELECT = "SELECT " + COLUMNS + " FROM " + TABLE;
    private static final String SELECT_WITH_PAYLOAD = "SELECT " + COLUMNS + ", input, output FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, trace_id, user_id, span_id, parent_span_id, name, "
        + "type, model, status, tool_call_id, input, output, input_tokens, output_tokens, cached_tokens, cost_usd, "
        + "cost_source, pricing_model_id, input_price_per_1m_tokens, output_price_per_1m_tokens, duration_ms, "
        + "error_message, attributes, started_at, completed_at, created_at) ";
    private static final int DELETE_BATCH_SIZE = 500;
    private static final int BULK_SIZE = 200;
    private static final long BULK_PAYLOAD_BYTES = 8L * 1024 * 1024;

    private static Span toSpan(PgSpanRow row) {
        var span = new Span();
        span.id = row.id;
        span.traceId = row.traceId;
        span.userId = row.userId;
        span.spanId = row.spanId;
        span.parentSpanId = row.parentSpanId;
        span.name = row.name;
        span.type = row.type != null ? SpanType.valueOf(row.type) : null;
        span.model = row.model;
        span.status = row.status != null ? SpanStatus.valueOf(row.status) : null;
        span.toolCallId = row.toolCallId;
        span.input = row.input;
        span.output = row.output;
        span.inputTokens = row.inputTokens;
        span.outputTokens = row.outputTokens;
        span.cachedTokens = row.cachedTokens;
        span.costUsd = row.costUsd;
        span.costSource = row.costSource;
        span.pricingModelId = row.pricingModelId;
        span.inputPricePer1MTokens = row.inputPricePer1MTokens;
        span.outputPricePer1MTokens = row.outputPricePer1MTokens;
        span.durationMs = row.durationMs;
        span.errorMessage = row.errorMessage;
        span.attributes = row.attributes != null ? JsonUtil.fromJson(ATTRIBUTES_TYPE, row.attributes) : null;
        span.startedAt = row.startedAt;
        span.completedAt = row.completedAt;
        span.createdAt = row.createdAt;
        return span;
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static String type(SpanType type) {
        return type == null ? null : type.name();
    }

    private static Object[] params(Span span) {
        return new Object[]{
            span.id, span.traceId, span.userId, span.spanId, span.parentSpanId, span.name, type(span.type), span.model,
            span.status != null ? span.status.name() : null, span.toolCallId, JsonSanitizer.text(span.input),
            JsonSanitizer.text(span.output), span.inputTokens, span.outputTokens, span.cachedTokens, span.costUsd,
            span.costSource, span.pricingModelId, span.inputPricePer1MTokens, span.outputPricePer1MTokens,
            span.durationMs, JsonSanitizer.text(span.errorMessage),
            span.attributes != null ? JsonUtil.toJson(span.attributes) : null,
            span.startedAt, span.completedAt, span.createdAt
        };
    }

    private static long payloadBytes(Span span) {
        long bytes = textBytes(span.input) + textBytes(span.output) + textBytes(span.errorMessage);
        if (span.attributes != null) bytes += JsonUtil.toJson(span.attributes).length();
        return bytes;
    }

    private static long textBytes(String value) {
        return value == null ? 0 : value.length();
    }

    private static final TypeReference<Map<String, String>> ATTRIBUTES_TYPE = new TypeReference<>() { };

    @Inject
    Database database;

    @Override
    public void insert(Span span) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
            params(span));
    }

    @Override
    public void bulkInsert(List<Span> spans) {
        // A span may carry megabytes of tool output; batching purely by row count made one
        // statement large enough to exhaust the database's memory during the legacy backfill.
        var batch = new ArrayList<Object[]>();
        long batchBytes = 0;
        for (var span : spans) {
            long bytes = payloadBytes(span);
            if (!batch.isEmpty() && (batch.size() >= BULK_SIZE || batchBytes + bytes > BULK_PAYLOAD_BYTES)) {
                flush(batch);
                batch.clear();
                batchBytes = 0;
            }
            batch.add(params(span));
            batchBytes += bytes;
        }
        if (!batch.isEmpty()) flush(batch);
    }

    private void flush(List<Object[]> batch) {
        database.batchExecute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
            + "ON CONFLICT DO NOTHING", batch);
    }

    @Override
    public Span find(String traceId, String spanId) {
        return database.selectOne(SELECT_WITH_PAYLOAD + " WHERE trace_id = ? AND span_id = ? LIMIT 1",
                PgSpanRow.class, traceId, spanId)
            .map(PgSpanStore::toSpan).orElse(null);
    }

    @Override
    public boolean existsToolCall(String traceId, String toolCallId) {
        return database.selectOne("SELECT id FROM " + TABLE + " WHERE trace_id = ? AND tool_call_id = ? LIMIT 1",
                PgTraceIdRow.class, traceId, toolCallId)
            .isPresent();
    }

    @Override
    public List<Span> summariesByTrace(String traceId, int limit) {
        return database.select(SELECT + " WHERE trace_id = ? ORDER BY started_at, id LIMIT ?", PgSpanRow.class, traceId, limit)
            .stream().map(PgSpanStore::toSpan).toList();
    }

    @Override
    public List<Span> summariesByTraces(List<String> traceIds, int limit) {
        if (traceIds.isEmpty()) return List.of();
        var params = new ArrayList<Object>(traceIds);
        params.add(limit);
        return database.select(SELECT + " WHERE trace_id IN (" + placeholders(traceIds.size()) + ") LIMIT ?",
                PgSpanRow.class, params.toArray())
            .stream().map(PgSpanStore::toSpan).toList();
    }

    @Override
    public List<Span> llmSpansByTraces(List<String> traceIds, int limit) {
        if (traceIds.isEmpty()) return List.of();
        var params = new ArrayList<Object>(traceIds);
        params.add(SpanType.LLM.name());
        params.add(limit);
        return database.select(SELECT_WITH_PAYLOAD + " WHERE trace_id IN (" + placeholders(traceIds.size())
                + ") AND type = ? ORDER BY started_at LIMIT ?", PgSpanRow.class, params.toArray())
            .stream().map(PgSpanStore::toSpan).toList();
    }

    @Override
    public List<Span> generations(int offset, int limit, String model, List<String> userIds, List<String> traceIds) {
        var sql = new StringBuilder(1024).append(SELECT_WITH_PAYLOAD).append(" WHERE type = ?");
        var params = new ArrayList<Object>();
        params.add(SpanType.LLM.name());
        if (model != null && !model.isEmpty()) {
            sql.append(" AND model = ?");
            params.add(model);
        }
        if (userIds != null) {
            if (traceIds.isEmpty()) {
                sql.append(" AND user_id IN (").append(placeholders(userIds.size())).append(')');
                params.addAll(userIds);
            } else {
                sql.append(" AND (user_id IN (").append(placeholders(userIds.size())).append(") OR trace_id IN (")
                    .append(placeholders(traceIds.size())).append("))");
                params.addAll(userIds);
                params.addAll(traceIds);
            }
        }
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return database.select(sql.toString(), PgSpanRow.class, params.toArray())
            .stream().map(PgSpanStore::toSpan).toList();
    }

    @Override
    public List<Span> before(ZonedDateTime cutoff, int offset, int limit) {
        return database.select(SELECT_WITH_PAYLOAD + " WHERE started_at < ? ORDER BY started_at, id LIMIT ? OFFSET ?",
                PgSpanRow.class, cutoff, limit, offset)
            .stream().map(PgSpanStore::toSpan).toList();
    }

    @Override
    public List<Span> pageByTrace(String traceId, int offset, int limit) {
        return database.select(SELECT_WITH_PAYLOAD + " WHERE trace_id = ? ORDER BY started_at, id LIMIT ? OFFSET ?",
                PgSpanRow.class, traceId, limit, offset)
            .stream().map(PgSpanStore::toSpan).toList();
    }

    @Override
    public long countBefore(ZonedDateTime cutoff) {
        var row = database.selectOne("SELECT count(*) AS count FROM " + TABLE + " WHERE started_at < ?",
                PgTraceCountRow.class, cutoff).orElse(null);
        return row == null || row.count == null ? 0 : row.count;
    }

    @Override
    public long deleteByTraceIds(List<String> traceIds) {
        long deleted = 0;
        for (int i = 0; i < traceIds.size(); i += DELETE_BATCH_SIZE) {
            var batch = traceIds.subList(i, Math.min(i + DELETE_BATCH_SIZE, traceIds.size()));
            deleted += database.execute("DELETE FROM " + TABLE + " WHERE trace_id IN (" + placeholders(batch.size()) + ")",
                batch.toArray());
        }
        return deleted;
    }

    @Override
    public long deleteByIds(List<String> ids) {
        long deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            var batch = ids.subList(i, Math.min(i + DELETE_BATCH_SIZE, ids.size()));
            deleted += database.execute("DELETE FROM " + TABLE + " WHERE id IN (" + placeholders(batch.size()) + ")",
                batch.toArray());
        }
        return deleted;
    }
}
