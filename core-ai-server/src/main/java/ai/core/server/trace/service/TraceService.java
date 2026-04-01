package ai.core.server.trace.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

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
        return traceCollection.find(query);
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
            enrichTokensFromSpans(trace);
        }
        return trace;
    }

    private void enrichTokensFromSpans(Trace trace) {
        if (trace.totalTokens != null && trace.totalTokens > 0) return;
        var spans = spans(trace.traceId);
        long inputTokens = 0;
        long outputTokens = 0;
        for (var span : spans) {
            if (span.inputTokens != null) inputTokens += span.inputTokens;
            if (span.outputTokens != null) outputTokens += span.outputTokens;
        }
        trace.inputTokens = inputTokens;
        trace.outputTokens = outputTokens;
        trace.totalTokens = inputTokens + outputTokens;
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

        return allTraces.stream()
            .collect(Collectors.groupingBy(t -> t.sessionId))
            .entrySet().stream()
            .map(entry -> {
                var traces = entry.getValue();
                traces.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
                long totalTokens = traces.stream().mapToLong(t -> t.totalTokens != null ? t.totalTokens : 0).sum();
                long totalDuration = traces.stream().mapToLong(t -> t.durationMs != null ? t.durationMs : 0).sum();
                long errorCount = traces.stream().filter(t -> t.status == TraceStatus.ERROR).count();
                var latestTrace = traces.getFirst();
                Map<String, Object> session = new java.util.LinkedHashMap<>();
                session.put("session_id", entry.getKey());
                session.put("trace_count", traces.size());
                session.put("total_tokens", totalTokens);
                session.put("total_duration_ms", totalDuration);
                session.put("error_count", errorCount);
                session.put("user_id", latestTrace.userId);
                session.put("last_trace_at", latestTrace.createdAt);
                session.put("first_trace_at", traces.getLast().createdAt);
                return session;
            })
            .sorted((a, b) -> ((ZonedDateTime) b.get("last_trace_at")).compareTo((ZonedDateTime) a.get("last_trace_at")))
            .skip(offset)
            .limit(limit)
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
        public String status;
        public String sessionId;
        public String userId;
        public ZonedDateTime startFrom;
        public ZonedDateTime startTo;
    }
}
