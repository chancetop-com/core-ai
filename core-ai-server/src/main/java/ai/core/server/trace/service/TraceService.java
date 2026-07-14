package ai.core.server.trace.service;

import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import ai.core.server.domain.User;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceFacetRow;
import ai.core.server.trace.domain.TraceStatus;

import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Xander
 */
public class TraceService {
    // Matches RFC-4122 UUID with or without dashes
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$");
    // Matches long hex strings (e.g. 32-char OpenTelemetry trace IDs without dashes)
    private static final Pattern LONG_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{16,}$");
    // Partial hex prefix: enough chars to be specific (UI shows 8-char session prefix) but shorter than full IDs
    private static final Pattern HEX_PREFIX_PATTERN = Pattern.compile("^[0-9a-fA-F]{6,}$");
    private static final int SCOPED_TRACE_ID_LOOKUP_LIMIT = 10_000;
    private static final int SEARCH_TRACE_SCAN_LIMIT = 10_000;
    private static final int FACET_LIMIT = 50;
    private static final int SESSION_SUMMARY_TRACE_LIMIT = 1000;
    private static final int USER_SEARCH_LIMIT = 10000;

    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<Span> spanCollection;
    @Inject
    MongoCollection<User> userCollection;

    public List<Trace> list(TraceListFilter filter) {
        if (requiresSearch(filter)) {
            return listWithSearch(filter);
        }

        var query = new Query();
        query.skip = filter.offset;
        query.limit = filter.limit;
        query.sort = Sorts.descending("created_at");

        var bsonFilters = buildFilters(filter);
        if (!bsonFilters.isEmpty()) {
            query.filter = bsonFilters.size() == 1 ? bsonFilters.getFirst() : Filters.and(bsonFilters);
        }
        var traces = traceCollection.find(query);
        TraceServiceHelper.enrichMetricsInBatch(traces, spanCollection);
        return traces;
    }

    public long count(TraceListFilter filter) {
        if (requiresSearch(filter)) {
            throw new UnsupportedOperationException("trace text search is counted in prev/next mode");
        }

        var bsonFilters = buildFilters(filter);
        if (bsonFilters.isEmpty()) return traceCollection.count(Filters.empty());
        return traceCollection.count(bsonFilters.size() == 1 ? bsonFilters.getFirst() : Filters.and(bsonFilters));
    }

    private List<Trace> listWithSearch(TraceListFilter filter) {
        var matches = searchCandidates(filter, searchFetchLimit(filter));
        var traces = TraceServiceHelper.page(matches, filter.offset, filter.limit);
        TraceServiceHelper.enrichMetricsInBatch(traces, spanCollection);
        return traces;
    }

    private List<Trace> searchCandidates(TraceListFilter filter, int limit) {
        var indexedFilters = buildFilters(filter);
        var matches = new LinkedHashMap<String, Trace>();
        var namePattern = TraceServiceHelper.compileNamePattern(filter.name);
        if (hasPlainTextQuery(filter)) {
            addUserMatches(matches, indexedFilters, matchingUserIds(filter.q), namePattern, limit);
        }
        addTraceTextMatches(matches, indexedFilters, filter, namePattern, limit);
        return matches.values().stream()
            .sorted(TraceServiceHelper::compareCreatedAtDesc)
            .toList();
    }

    private int searchFetchLimit(TraceListFilter filter) {
        var requested = Math.max(filter.limit, filter.offset + filter.limit);
        return Math.clamp(requested, 1, SEARCH_TRACE_SCAN_LIMIT);
    }

    private List<Bson> buildFilters(TraceListFilter filter) {
        List<Bson> bsonFilters = new ArrayList<>();
        addQueryFilter(bsonFilters, filter.q);
        // name is an advanced raw regex, evaluated in Java to avoid unindexed Mongo regex scans
        if (filter.type != null && !filter.type.isEmpty()) {
            bsonFilters.add(Filters.eq("type", filter.type));
        }
        addSourceFilter(bsonFilters, filter.source);
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
        return bsonFilters;
    }

    // q is the user-friendly search. Strategy:
    //   - Full UUID / 32-char trace ID → exact match on id fields only, so the OR stays on indexes
    //   - 6+ hex chars (e.g. an 8-char session prefix shown in the UI) → anchored prefix match on id fields
    //   - Plain text → user_id index matches plus bounded in-memory name/agent matching
    // Ids are stored as lowercase hex, so prefix regexes are lowercased without the "i" flag to stay on the index.
    private void addQueryFilter(List<Bson> bsonFilters, String q) {
        if (q == null || q.isEmpty()) return;
        var trimmed = q.trim();
        if (UUID_PATTERN.matcher(trimmed).matches() || LONG_HEX_PATTERN.matcher(trimmed).matches()) {
            bsonFilters.add(Filters.or(TraceServiceHelper.fullIdClauses(trimmed)));
        } else if (HEX_PREFIX_PATTERN.matcher(trimmed).matches()) {
            var prefix = "^" + Pattern.quote(trimmed.toLowerCase(Locale.ROOT));
            bsonFilters.add(Filters.or(
                Filters.regex("session_id", prefix),
                Filters.regex("trace_id", prefix)));
        }
    }

    private void addSourceFilter(List<Bson> bsonFilters, String source) {
        if (source == null || source.isEmpty()) return;
        // Legacy traces predate the source field; treat missing source as "chat"
        if ("chat".equals(source)) {
            bsonFilters.add(Filters.or(
                Filters.eq("source", "chat"),
                Filters.exists("source", false),
                Filters.eq("source", null),
                Filters.eq("source", "")));
        } else {
            bsonFilters.add(Filters.eq("source", source));
        }
    }

    private boolean requiresSearch(TraceListFilter filter) {
        return hasPlainTextQuery(filter) || TraceServiceHelper.hasText(filter.name);
    }

    private boolean hasPlainTextQuery(TraceListFilter filter) {
        if (filter.q == null || filter.q.isBlank()) return false;
        var q = filter.q.trim();
        return !UUID_PATTERN.matcher(q).matches()
            && !LONG_HEX_PATTERN.matcher(q).matches()
            && !HEX_PREFIX_PATTERN.matcher(q).matches();
    }

    private void addUserMatches(Map<String, Trace> matches, List<Bson> indexedFilters, Set<String> userIds, Pattern namePattern, int limit) {
        if (userIds.isEmpty()) return;
        var filters = new ArrayList<>(indexedFilters);
        filters.add(Filters.in("user_id", userIds));
        traceCollection.find(TraceServiceHelper.sortedTraceQuery(filters, limit)).stream()
            .filter(trace -> TraceServiceHelper.matchesNamePattern(trace, namePattern))
            .forEach(trace -> TraceServiceHelper.putTrace(matches, trace));
    }

    private void addTraceTextMatches(Map<String, Trace> matches, List<Bson> indexedFilters, TraceListFilter filter, Pattern namePattern, int limit) {
        if (!hasPlainTextQuery(filter) && namePattern == null) return;
        var query = hasPlainTextQuery(filter) ? filter.q.trim() : null;
        traceCollection.find(TraceServiceHelper.sortedTraceQuery(indexedFilters, limit)).stream()
            .filter(trace -> query == null || matchesTraceTextQuery(trace, query))
            .filter(trace -> TraceServiceHelper.matchesNamePattern(trace, namePattern))
            .forEach(trace -> TraceServiceHelper.putTrace(matches, trace));
    }

    private Set<String> matchingUserIds(String query) {
        var userIds = new LinkedHashSet<String>();
        if (!TraceServiceHelper.hasText(query) || userCollection == null) return userIds;
        var needle = query.trim();
        if (needle.contains("@")) {
            userIds.add(needle.toLowerCase(Locale.ROOT));
        }

        try {
            var userQuery = new Query();
            userQuery.filter = Filters.exists("email");
            userQuery.limit = USER_SEARCH_LIMIT;
            var users = userCollection.find(userQuery);
            if (users == null) return userIds;
            for (var user : users) {
                if (matchesUser(user, needle)) {
                    addUserId(userIds, user);
                }
            }
        } catch (RuntimeException ignored) {
            // Exact email lookup above remains available if listing users is blocked by the cluster.
        }
        return userIds;
    }

    private boolean matchesUser(User user, String needle) {
        return user != null
            && (TraceServiceHelper.containsIgnoreCase(user.id, needle)
            || TraceServiceHelper.containsIgnoreCase(user.name, needle)
            || TraceServiceHelper.containsIgnoreCase(user.email, needle));
    }

    private void addUserId(Set<String> userIds, User user) {
        if (TraceServiceHelper.hasText(user.id)) {
            userIds.add(user.id);
        } else if (TraceServiceHelper.hasText(user.email)) {
            userIds.add(user.email.toLowerCase(Locale.ROOT));
        }
    }

    private boolean matchesTraceTextQuery(Trace trace, String query) {
        return TraceServiceHelper.containsIgnoreCase(trace.name, query)
            || TraceServiceHelper.containsIgnoreCase(trace.agentName, query)
            || TraceServiceHelper.containsIgnoreCase(trace.userId, query);
    }

    public List<Map<String, Object>> facets(String field, TraceListFilter filter) {
        var mongoField = TraceServiceHelper.mongoFieldName(field);
        if (mongoField == null) return List.of();
        if (requiresSearch(filter)) {
            return facetsInMemory(field, filter);
        }

        var bsonFilters = buildFilters(filter);
        // ne(null) also excludes missing fields and keeps clean index bounds (notablescan-safe on dev)
        bsonFilters.add(Filters.ne(mongoField, null));
        bsonFilters.add(Filters.ne(mongoField, ""));
        var aggregate = new Aggregate<TraceFacetRow>();
        aggregate.resultClass = TraceFacetRow.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(bsonFilters)),
            Aggregates.group("$" + mongoField, Accumulators.sum("count", 1)),
            Aggregates.sort(Sorts.descending("count")),
            Aggregates.limit(FACET_LIMIT));
        return traceCollection.aggregate(aggregate).stream()
            .map(facetRow -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("value", facetRow.value);
                row.put("count", facetRow.count);
                return row;
            })
            .collect(Collectors.toList());
    }

    private List<Map<String, Object>> facetsInMemory(String field, TraceListFilter filter) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var trace : searchCandidates(filter, SEARCH_TRACE_SCAN_LIMIT)) {
            var value = TraceServiceHelper.facetValue(trace, field);
            if (value == null || value.isBlank()) continue;
            counts.put(value, counts.getOrDefault(value, 0) + 1);
        }
        return counts.entrySet().stream()
            .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
            .limit(FACET_LIMIT)
            .map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("value", entry.getKey());
                row.put("count", entry.getValue());
                return row;
            })
            .collect(Collectors.toList());
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
        if (trace != null && TraceServiceHelper.needsEnrichment(trace)) {
            TraceServiceHelper.enrichMetrics(trace, spans(trace.traceId));
        }
        return trace;
    }

    public List<Span> spans(String traceId) {
        var query = new Query();
        query.filter = Filters.eq("trace_id", traceId);
        query.sort = Sorts.ascending("started_at");
        query.limit = TraceServiceHelper.MAX_SPANS_PER_TRACE;
        return spanCollection.find(query);
    }

    public List<Span> generations(int offset, int limit, String model, String userId) {
        var query = new Query();
        query.skip = offset;
        query.limit = limit;
        query.sort = Sorts.descending("started_at");

        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("type", SpanType.LLM));
        if (model != null && !model.isEmpty()) {
            filters.add(Filters.eq("model", model));
        }
        if (userId != null && !userId.isEmpty()) {
            var traceIds = traceIdsForUser(userId);
            if (traceIds.isEmpty()) {
                filters.add(Filters.eq("user_id", userId));
            } else {
                filters.add(Filters.or(
                    Filters.eq("user_id", userId),
                    Filters.in("trace_id", traceIds)));
            }
        }
        query.filter = Filters.and(filters);
        return spanCollection.find(query);
    }

    private List<String> traceIdsForUser(String userId) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("created_at");
        query.limit = SCOPED_TRACE_ID_LOOKUP_LIMIT;
        return traceCollection.find(query).stream()
            .map(trace -> trace.traceId)
            .filter(traceId -> traceId != null && !traceId.isEmpty())
            .toList();
    }

    // Aggregates one session's traces for the trace-page summary bar; walks the session_id index only
    public Map<String, Object> sessionSummary(String sessionId, String userId) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("session_id", sessionId));
        if (userId != null && !userId.isEmpty()) {
            filters.add(Filters.eq("user_id", userId));
        }
        var sessionFilter = filters.size() == 1 ? filters.getFirst() : Filters.and(filters);

        var query = new Query();
        query.filter = sessionFilter;
        query.sort = Sorts.descending("created_at");
        query.limit = SESSION_SUMMARY_TRACE_LIMIT;
        var traces = traceCollection.find(query);
        if (traces.isEmpty()) return null;
        TraceServiceHelper.enrichMetricsInBatch(traces, spanCollection);

        // trace_count and first trace stay exact even when the aggregate window above is capped
        var traceCount = traceCollection.count(sessionFilter);
        var latestTrace = traces.getFirst();
        var firstTrace = traceCount > traces.size() ? findFirstTrace(sessionFilter) : traces.getLast();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("session_id", sessionId);
        session.put("trace_count", traceCount);
        session.put("total_tokens", traces.stream().mapToLong(t -> t.totalTokens != null ? t.totalTokens : 0).sum());
        session.put("total_cached_tokens", traces.stream().mapToLong(t -> t.cachedTokens != null ? t.cachedTokens : 0).sum());
        session.put("total_cost_usd", traces.stream().mapToDouble(t -> t.costUsd != null ? t.costUsd : 0.0).sum());
        session.put("total_duration_ms", traces.stream().mapToLong(t -> t.durationMs != null ? t.durationMs : 0).sum());
        session.put("error_count", traces.stream().filter(t -> t.status == TraceStatus.ERROR).count());
        session.put("user_id", latestTrace.userId);
        session.put("last_trace_at", latestTrace.createdAt);
        session.put("first_trace_at", firstTrace.createdAt);
        session.put("first_request", TracePreviewExtractor.extract(firstTrace.input));
        return session;
    }

    private Trace findFirstTrace(Bson sessionFilter) {
        var query = new Query();
        query.filter = sessionFilter;
        query.sort = Sorts.ascending("created_at");
        query.limit = 1;
        return traceCollection.find(query).getFirst();
    }

    public void saveTrace(Trace trace) {
        traceCollection.insert(trace);
    }

    public void saveSpan(Span span) {
        spanCollection.insert(span);
    }
}
