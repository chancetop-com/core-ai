package ai.core.server.trace;

import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.util.MigrationErrors;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * @author stephen
 */
public class MongoSpanStore implements SpanStore {
    private static final int DELETE_BATCH_SIZE = 500;

    public static MongoSpanStore of(MongoCollection<Span> collection) {
        var store = new MongoSpanStore();
        store.spanCollection = collection;
        return store;
    }

    private static long safe(Long value) {
        return value != null ? value : 0L;
    }

    private static Double percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return null;
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return (double) sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static long number(Document document, String key) {
        var value = document.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double decimal(Document document, String key) {
        var value = document.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    @Inject
    MongoCollection<Span> spanCollection;

    @Override
    public void insert(Span span) {
        spanCollection.insert(span);
    }

    @Override
    public boolean insertIfAbsent(Span span) {
        try {
            spanCollection.insert(span);
            return true;
        } catch (RuntimeException e) {
            if (MigrationErrors.isDuplicateKey(e)) return false;
            throw e;
        }
    }

    @Override
    public void bulkInsert(List<Span> spans) {
        if (!spans.isEmpty()) spanCollection.bulkInsert(spans);
    }

    @Override
    public List<TokenTotals> tokenTotals(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return List.of();
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.in("trace_id", traceIds)),
            Aggregates.group("$trace_id",
                Accumulators.sum("inputTokens", "$input_tokens"),
                Accumulators.sum("outputTokens", "$output_tokens"),
                Accumulators.sum("cachedTokens", "$cached_tokens"),
                Accumulators.sum("costUsd", "$cost_usd")));
        return spanCollection.aggregate(aggregate).stream()
            .map(document -> new TokenTotals(document.getString("_id"),
                number(document, "inputTokens"),
                number(document, "outputTokens"),
                number(document, "cachedTokens"),
                decimal(document, "costUsd")))
            .toList();
    }

    @Override
    public Span find(String traceId, String spanId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("trace_id", traceId), Filters.eq("span_id", spanId));
        query.limit = 1;
        var results = spanCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public boolean existsToolCall(String traceId, String toolCallId) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("trace_id", traceId), Filters.eq("tool_call_id", toolCallId));
        query.projection = Projections.include("_id");
        query.limit = 1;
        return !spanCollection.find(query).isEmpty();
    }

    @Override
    public List<Span> summariesByTrace(String traceId, int limit) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.sort = Sorts.ascending("started_at");
        query.limit = limit;
        query.projection = Projections.exclude("input", "output");
        return spanCollection.find(query);
    }

    @Override
    public List<Span> summariesByTraces(List<String> traceIds, int limit) {
        if (traceIds.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.in("trace_id", traceIds);
        query.limit = limit;
        query.projection = Projections.exclude("input", "output");
        return spanCollection.find(query);
    }

    @Override
    public List<Span> llmSpansByTraces(List<String> traceIds, int limit) {
        if (traceIds.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.and(Filters.in("trace_id", traceIds), Filters.eq("type", SpanType.LLM.name()));
        query.sort = Sorts.ascending("started_at");
        query.limit = limit;
        return spanCollection.find(query);
    }

    @Override
    public List<Span> generations(int offset, int limit, String model, List<String> userIds, List<String> traceIds) {
        var query = new Query();
        query.skip = offset;
        query.limit = limit;
        query.sort = Sorts.descending("started_at");

        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("type", SpanType.LLM));
        if (model != null && !model.isEmpty()) {
            filters.add(Filters.eq("model", model));
        }
        if (userIds != null) {
            if (traceIds.isEmpty()) {
                filters.add(Filters.in("user_id", userIds));
            } else {
                filters.add(Filters.or(Filters.in("user_id", userIds), Filters.in("trace_id", traceIds)));
            }
        }
        query.filter = Filters.and(filters);
        return spanCollection.find(query);
    }

    @Override
    public List<LlmThroughputRow> llmThroughput(ZonedDateTime from, ZonedDateTime to, int bucketMinutes) {
        long bucketMillis = bucketMinutes * 60_000L;
        var buckets = new TreeMap<Long, LlmThroughputRow>();
        spanCollection.forEach(llmQuery(from, to), span -> {
            var row = buckets.computeIfAbsent(bucketStart(span, bucketMillis), start -> {
                var value = new LlmThroughputRow();
                value.bucketStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), ZonedDateTime.now().getZone());
                value.calls = 0L;
                value.errors = 0L;
                value.inputTokens = 0L;
                value.outputTokens = 0L;
                value.cachedTokens = 0L;
                value.costUsd = 0.0;
                return value;
            });
            row.calls++;
            if (span.status == SpanStatus.ERROR) row.errors++;
            row.inputTokens += safe(span.inputTokens);
            row.outputTokens += safe(span.outputTokens);
            row.cachedTokens += safe(span.cachedTokens);
            row.costUsd += span.costUsd != null ? span.costUsd : 0.0;
        });
        return new ArrayList<>(buckets.values());
    }

    @Override
    public List<LlmModelMetricsRow> llmModelMetrics(ZonedDateTime from, ZonedDateTime to) {
        var models = new LinkedHashMap<String, ModelAccumulator>();
        spanCollection.forEach(llmQuery(from, to), span -> models
            .computeIfAbsent(span.model != null ? span.model : "(unknown)", ignored -> new ModelAccumulator())
            .accept(span));
        return models.values().stream().map(ModelAccumulator::toRow).toList();
    }

    private Query llmQuery(ZonedDateTime from, ZonedDateTime to) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("type", SpanType.LLM), Filters.gte("started_at", from), Filters.lt("started_at", to));
        query.projection = Projections.include("model", "status", "input_tokens", "output_tokens", "cached_tokens",
            "cost_usd", "duration_ms", "ttft_ms", "started_at");
        return query;
    }

    private long bucketStart(Span span, long bucketMillis) {
        return span.startedAt.toInstant().toEpochMilli() / bucketMillis * bucketMillis;
    }

    @Override
    public List<Span> before(ZonedDateTime cutoff, int offset, int limit) {
        var query = new Query();
        query.filter = Filters.lt("started_at", cutoff);
        query.sort = Sorts.ascending("started_at");
        query.skip = offset;
        query.limit = limit;
        return spanCollection.find(query);
    }

    @Override
    public List<Span> pageByTrace(String traceId, int offset, int limit) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.skip = offset;
        query.limit = limit;
        return spanCollection.find(query);
    }

    @Override
    public long countBefore(ZonedDateTime cutoff) {
        return spanCollection.count(Filters.lt("started_at", cutoff));
    }

    @Override
    public long deleteByTraceIds(List<String> traceIds) {
        long deleted = 0;
        for (int i = 0; i < traceIds.size(); i += DELETE_BATCH_SIZE) {
            var batch = traceIds.subList(i, Math.min(i + DELETE_BATCH_SIZE, traceIds.size()));
            deleted += spanCollection.delete(Filters.in("trace_id", batch));
        }
        return deleted;
    }

    @Override
    public long deleteByIds(List<String> ids) {
        long deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            var batch = ids.subList(i, Math.min(i + DELETE_BATCH_SIZE, ids.size()));
            deleted += spanCollection.delete(Filters.in("_id", batch));
        }
        return deleted;
    }

    /**
     * Java-side rollup for the legacy Mongo store. Percentiles are computed over a bounded sample so a
     * week of traffic cannot pull every latency into memory; the PG store leaves this to SQL.
     */
    private final class ModelAccumulator {
        private static final int MAX_PERCENTILE_SAMPLES = 20_000;

        private long calls;
        private long errors;
        private long inputTokens;
        private long outputTokens;
        private long cachedTokens;
        private double costUsd;
        private long durationSum;
        private long ttftSum;
        private long decodeSpeedSamples;
        private double decodeSpeedSum;
        private final List<Long> ttfts = new ArrayList<>();
        private final List<Long> durations = new ArrayList<>();

        void accept(Span span) {
            calls++;
            if (span.status == SpanStatus.ERROR) errors++;
            inputTokens += safe(span.inputTokens);
            outputTokens += safe(span.outputTokens);
            cachedTokens += safe(span.cachedTokens);
            costUsd += span.costUsd != null ? span.costUsd : 0.0;
            if (span.ttftMs != null) {
                ttftSum += span.ttftMs;
                if (ttfts.size() < MAX_PERCENTILE_SAMPLES) ttfts.add(span.ttftMs);
            }
            if (span.durationMs != null) {
                durationSum += span.durationMs;
                if (durations.size() < MAX_PERCENTILE_SAMPLES) durations.add(span.durationMs);
                if (span.ttftMs != null && span.outputTokens != null && span.outputTokens > 0 && span.durationMs > span.ttftMs) {
                    decodeSpeedSum += span.outputTokens * 1000.0 / (span.durationMs - span.ttftMs);
                    decodeSpeedSamples++;
                }
            }
        }

        LlmModelMetricsRow toRow() {
            var row = new LlmModelMetricsRow();
            row.calls = calls;
            row.errors = errors;
            row.callsWithTtft = (long) ttfts.size();
            row.inputTokens = inputTokens;
            row.outputTokens = outputTokens;
            row.cachedTokens = cachedTokens;
            row.costUsd = costUsd;
            row.avgTtftMs = ttfts.isEmpty() ? null : (double) ttftSum / ttfts.size();
            row.p50TtftMs = percentile(ttfts, 0.5);
            row.p95TtftMs = percentile(ttfts, 0.95);
            row.avgDurationMs = calls == 0 ? null : (double) durationSum / calls;
            row.p50DurationMs = percentile(durations, 0.5);
            row.p95DurationMs = percentile(durations, 0.95);
            row.avgOutputTokensPerSecond = decodeSpeedSamples == 0 ? null : decodeSpeedSum / decodeSpeedSamples;
            return row;
        }
    }
}

