package ai.core.server.trace.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;

import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Xander
 */
public class TraceService {
    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<Span> spanCollection;

    public List<Trace> list(TraceListFilter filter) {
        var query = new Query();
        query.skip = filter.offset;
        query.limit = filter.limit;
        query.sort = Sorts.descending("created_at");

        List<Bson> bsonFilters = new ArrayList<>();
        if (filter.name != null && !filter.name.isEmpty()) {
            bsonFilters.add(Filters.regex("name", filter.name, "i"));
        }
        if (filter.type != null && !filter.type.isEmpty()) {
            bsonFilters.add(Filters.eq("type", filter.type));
        }
        if (filter.source != null && !filter.source.isEmpty()) {
            bsonFilters.add(Filters.eq("source", filter.source));
        }
        if (filter.agentName != null && !filter.agentName.isEmpty()) {
            bsonFilters.add(Filters.eq("agent_name", filter.agentName));
        }
        if (filter.model != null && !filter.model.isEmpty()) {
            bsonFilters.add(Filters.eq("model", filter.model));
        }
        if (filter.status != null && !filter.status.isEmpty()) {
            bsonFilters.add(Filters.eq("status", TraceStatus.valueOf(filter.status)));
        }
        if (filter.sessionId != null && !filter.sessionId.isEmpty()) {
            bsonFilters.add(Filters.eq("session_id", filter.sessionId));
        }
        if (filter.userId != null && !filter.userId.isEmpty()) {
            bsonFilters.add(Filters.eq("user_id", filter.userId));
        }
        if (filter.startFrom != null) {
            bsonFilters.add(Filters.gte("started_at", filter.startFrom));
        }
        if (filter.startTo != null) {
            bsonFilters.add(Filters.lte("started_at", filter.startTo));
        }
        if (!bsonFilters.isEmpty()) {
            query.filter = bsonFilters.size() == 1 ? bsonFilters.getFirst() : Filters.and(bsonFilters);
        }
        var traces = traceCollection.find(query);
        traces.forEach(this::enrichMetricsFromSpans);
        return traces;
    }

    public Trace get(String traceId) {
        Trace trace = traceCollection.get(traceId).orElse(null);
        if (trace == null) {
            var query = new Query();
            query.filter = Filters.eq("trace_id", traceId);
            query.limit = 1;
            var results = traceCollection.find(query);
            trace = results.isEmpty() ? null : results.getFirst();
        }
        if (trace != null) {
            enrichMetricsFromSpans(trace);
        }
        return trace;
    }

    private void enrichMetricsFromSpans(Trace trace) {
        var needsTokens = trace.totalTokens == null || trace.totalTokens == 0;
        var needsCachedTokens = trace.cachedTokens == null || needsTokens;
        var needsCost = trace.costUsd == null || needsTokens;
        if (!needsTokens && !needsCachedTokens && !needsCost) return;

        var spans = spans(trace.traceId);
        long inputTokens = 0;
        long outputTokens = 0;
        long cachedTokens = 0;
        double costUsd = 0.0;
        boolean hasCost = false;
        for (var span : spans) {
            if (span.inputTokens != null) inputTokens += span.inputTokens;
            if (span.outputTokens != null) outputTokens += span.outputTokens;
            var spanCachedTokens = span.cachedTokens != null ? span.cachedTokens : parseCachedTokens(span.attributes);
            if (spanCachedTokens != null) cachedTokens += spanCachedTokens;
            var spanCostUsd = span.costUsd != null
                ? span.costUsd
                : LLMModelContextRegistry.getInstance().estimateCostUsd(span.model,
                    safeLong(span.inputTokens), safeLong(span.outputTokens), safeLong(spanCachedTokens));
            if (spanCostUsd != null) {
                costUsd += spanCostUsd;
                hasCost = true;
            }
        }
        if (needsTokens) {
            trace.inputTokens = inputTokens;
            trace.outputTokens = outputTokens;
            trace.totalTokens = inputTokens + outputTokens;
        }
        if (needsCachedTokens) trace.cachedTokens = cachedTokens;
        if (needsCost && hasCost) trace.costUsd = costUsd;
    }

    private Long parseCachedTokens(Map<String, String> attributes) {
        if (attributes == null) return null;
        for (var key : List.of(
            "gen_ai.usage.cached_tokens",
            "gen_ai.usage.prompt_tokens_details.cached_tokens",
            "gen_ai.usage.input_tokens_details.cached_tokens",
            "usage.prompt_tokens_details.cached_tokens",
            "prompt_tokens_details.cached_tokens")) {
            var value = attributes.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
        return null;
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    public List<Span> spans(String traceId) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.sort = Sorts.ascending("started_at");
        return spanCollection.find(query);
    }

    public List<Span> generations(int offset, int limit, String model) {
        var query = new Query();
        query.skip = offset;
        query.limit = limit;
        query.sort = Sorts.descending("started_at");

        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("type", SpanType.LLM));
        if (model != null && !model.isEmpty()) {
            filters.add(Filters.eq("model", model));
        }
        query.filter = Filters.and(filters);
        return spanCollection.find(query);
    }

    public List<Map<String, Object>> sessions(int offset, int limit) {
        var query = new Query();
        query.filter = Filters.and(Filters.exists("session_id"), Filters.ne("session_id", null));
        query.sort = Sorts.descending("created_at");
        var allTraces = traceCollection.find(query);
        var groupedSessions = new ArrayList<>(allTraces.stream()
            .collect(Collectors.groupingBy(t -> t.sessionId))
            .entrySet());
        groupedSessions.forEach(entry -> entry.getValue().sort((a, b) -> b.createdAt.compareTo(a.createdAt)));

        return groupedSessions.stream()
            .sorted((a, b) -> b.getValue().getFirst().createdAt.compareTo(a.getValue().getFirst().createdAt))
            .skip(offset)
            .limit(limit)
            .map(entry -> {
                var traces = entry.getValue();
                traces.forEach(this::enrichMetricsFromSpans);
                long totalTokens = traces.stream().mapToLong(t -> t.totalTokens != null ? t.totalTokens : 0).sum();
                long totalCachedTokens = traces.stream().mapToLong(t -> t.cachedTokens != null ? t.cachedTokens : 0).sum();
                double totalCostUsd = traces.stream().mapToDouble(t -> t.costUsd != null ? t.costUsd : 0.0).sum();
                long totalDuration = traces.stream().mapToLong(t -> t.durationMs != null ? t.durationMs : 0).sum();
                long errorCount = traces.stream().filter(t -> t.status == TraceStatus.ERROR).count();
                var latestTrace = traces.getFirst();
                var firstTrace = traces.getLast();
                Map<String, Object> session = new java.util.LinkedHashMap<>();
                session.put("session_id", entry.getKey());
                session.put("trace_count", traces.size());
                session.put("total_tokens", totalTokens);
                session.put("total_cached_tokens", totalCachedTokens);
                session.put("total_cost_usd", totalCostUsd);
                session.put("total_duration_ms", totalDuration);
                session.put("error_count", errorCount);
                session.put("user_id", latestTrace.userId);
                session.put("last_trace_at", latestTrace.createdAt);
                session.put("first_trace_at", firstTrace.createdAt);
                session.put("first_request", firstTrace.input);
                return session;
            })
            .collect(Collectors.toList());
    }

    public void saveTrace(Trace trace) {
        traceCollection.insert(trace);
    }

    public void saveSpan(Span span) {
        spanCollection.insert(span);
    }

    public static class TraceListFilter {
        public int offset;
        public int limit = 20;
        public String name;
        public String type;        // agent | llm_call | external
        public String source;      // chat | test | api | a2a | scheduled | llm_test | llm_api | external
        public String agentName;
        public String model;
        public String status;
        public String sessionId;
        public String userId;
        public ZonedDateTime startFrom;
        public ZonedDateTime startTo;
    }
}
