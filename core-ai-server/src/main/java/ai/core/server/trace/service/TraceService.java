package ai.core.server.trace.service;

import core.framework.inject.Inject;

import ai.core.server.domain.User;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceFacet;
import ai.core.server.trace.TraceQuery;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;

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
    TraceStore traceStore;
    @Inject
    SpanStore spanStore;
    @Inject
    ai.core.server.user.UserStore userStore;
    @Inject
    TracePayloadStore payloadStore;

    public List<Trace> list(TraceListFilter filter) {
        if (requiresSearch(filter)) {
            return listWithSearch(filter);
        }
        var traces = traceStore.list(query(filter));
        TraceServiceHelper.enrichMetricsInBatch(traces, spanStore);
        return traces;
    }

    public long count(TraceListFilter filter) {
        if (requiresSearch(filter)) {
            throw new UnsupportedOperationException("trace text search is counted in prev/next mode");
        }
        return traceStore.count(query(filter));
    }

    private List<Trace> listWithSearch(TraceListFilter filter) {
        var matches = searchCandidates(filter, searchFetchLimit(filter));
        var traces = TraceServiceHelper.page(matches, filter.offset, filter.limit);
        TraceServiceHelper.enrichMetricsInBatch(traces, spanStore);
        return traces;
    }

    private List<Trace> searchCandidates(TraceListFilter filter, int limit) {
        var matches = new LinkedHashMap<String, Trace>();
        var namePattern = TraceServiceHelper.compileNamePattern(filter.name);
        if (hasPlainTextQuery(filter)) {
            addUserMatches(matches, filter, matchingUserIds(filter.q), namePattern, limit);
        }
        addTraceTextMatches(matches, filter, namePattern, limit);
        return matches.values().stream()
            .sorted(TraceServiceHelper::compareCreatedAtDesc)
            .toList();
    }

    private int searchFetchLimit(TraceListFilter filter) {
        var requested = Math.max(filter.limit, filter.offset + filter.limit);
        return Math.clamp(requested, 1, SEARCH_TRACE_SCAN_LIMIT);
    }

    private TraceQuery query(TraceListFilter filter) {
        var query = new TraceQuery();
        query.skip = filter.offset;
        query.limit = filter.limit;
        addQueryFilter(query, filter.q);
        // name is an advanced raw regex, evaluated in Java to avoid unindexed regex scans
        query.type = filter.type;
        query.source = filter.source;
        query.agentName = filter.agentName;
        query.model = filter.model;
        query.status = filter.status;
        query.sessionId = filter.sessionId;
        query.userId = filter.userId;
        query.userIds = filter.userIds;
        query.startFrom = filter.startFrom;
        query.startTo = filter.startTo;
        return query;
    }

    // q is the user-friendly search. Strategy:
    //   - Full UUID / 32-char trace ID → exact match on id fields only, so the OR stays on indexes
    //   - 6+ hex chars (e.g. an 8-char session prefix shown in the UI) → anchored prefix match on id fields
    //   - Plain text → user_id index matches plus bounded in-memory name/agent matching
    private void addQueryFilter(TraceQuery query, String q) {
        if (q == null || q.isEmpty()) return;
        var trimmed = q.trim();
        if (UUID_PATTERN.matcher(trimmed).matches() || LONG_HEX_PATTERN.matcher(trimmed).matches()) {
            query.exactId = trimmed;
        } else if (HEX_PREFIX_PATTERN.matcher(trimmed).matches()) {
            query.idPrefix = trimmed;
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

    private void addUserMatches(Map<String, Trace> matches, TraceListFilter filter, Set<String> userIds, Pattern namePattern, int limit) {
        if (userIds.isEmpty()) return;
        var query = query(filter);
        query.skip = 0;
        query.limit = limit;
        query.extraUserIds = List.copyOf(userIds);
        traceStore.list(query).stream()
            .filter(trace -> TraceServiceHelper.matchesNamePattern(trace, namePattern))
            .forEach(trace -> TraceServiceHelper.putTrace(matches, trace));
    }

    private void addTraceTextMatches(Map<String, Trace> matches, TraceListFilter filter, Pattern namePattern, int limit) {
        if (namePattern == null && !hasPlainTextQuery(filter)) return;
        var query = query(filter);
        query.skip = 0;
        query.limit = limit;
        traceStore.list(query).stream()
            .filter(trace -> (filter.q == null || matchesTraceTextQuery(trace, filter.q.trim()))
                && TraceServiceHelper.matchesNamePattern(trace, namePattern))
            .forEach(trace -> TraceServiceHelper.putTrace(matches, trace));
    }

    private Set<String> matchingUserIds(String query) {
        var userIds = new LinkedHashSet<String>();
        if (!TraceServiceHelper.hasText(query) || userStore == null) return userIds;
        var needle = query.trim();
        if (needle.contains("@")) {
            userIds.add(needle.toLowerCase(Locale.ROOT));
        }

        try {
            var users = userStore.listUsersWithEmail(USER_SEARCH_LIMIT);
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
        var facet = TraceFacet.from(field);
        if (facet == null) return List.of();
        if (requiresSearch(filter)) {
            return facetsInMemory(field, filter);
        }
        return traceStore.facets(query(filter), facet, FACET_LIMIT).stream()
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
        var trace = traceStore.get(traceId, traceId);
        if (trace != null && TraceServiceHelper.needsEnrichment(trace)) {
            TraceServiceHelper.enrichMetrics(trace, spans(trace.traceId));
        }
        return trace;
    }

    public Trace getWithPayload(String traceId) {
        var trace = get(traceId);
        if (trace != null && payloadStore != null) payloadStore.hydrate(trace, false);
        return trace;
    }

    public List<Span> spans(String traceId) {
        // Metadata-only query: the timeline UI never needs the (potentially huge) payloads.
        return spanStore.summariesByTrace(traceId, TraceServiceHelper.MAX_SPANS_PER_TRACE);
    }

    public Span span(String traceId, String spanId) {
        var span = spanStore.find(traceId, spanId);
        if (span == null) return null;
        TraceServiceHelper.stripDuplicatedPayloadAttributes(span);
        if (payloadStore != null) payloadStore.hydrate(span, false);
        return span;
    }

    public List<Span> generations(int offset, int limit, String model, List<String> userIds) {
        if (userIds != null && userIds.isEmpty()) return List.of();
        List<String> traceIds = userIds != null ? traceIdsForUsers(userIds) : List.of();
        return spanStore.generations(offset, limit, model, userIds, traceIds);
    }

    private List<String> traceIdsForUsers(List<String> userIds) {
        var query = new TraceQuery();
        query.userIds = userIds;
        return traceStore.traceIds(query, SCOPED_TRACE_ID_LOOKUP_LIMIT);
    }

    // Aggregates one session's traces for the trace-page summary bar; walks the session_id index only
    public Map<String, Object> sessionSummary(String sessionId, List<String> userIds) {
        if (userIds != null && userIds.isEmpty()) return null;
        var query = new TraceQuery();
        query.sessionId = sessionId;
        query.userIds = userIds;
        query.limit = SESSION_SUMMARY_TRACE_LIMIT;
        var traces = traceStore.list(query);
        if (traces.isEmpty()) return null;
        TraceServiceHelper.enrichMetricsInBatch(traces, spanStore);

        // trace_count and first trace stay exact even when the aggregate window above is capped
        var traceCount = traceStore.count(query);
        var latestTrace = traces.getFirst();
        var firstTrace = traceCount > traces.size() ? findFirstTrace(sessionId, userIds) : traces.getLast();
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

    private Trace findFirstTrace(String sessionId, List<String> userIds) {
        var query = new TraceQuery();
        query.sessionId = sessionId;
        query.userIds = userIds;
        query.ascending = true;
        query.limit = 1;
        return traceStore.list(query).getFirst();
    }

    public void saveTrace(Trace trace) {
        traceStore.insert(trace);
    }

    public void saveSpan(Span span) {
        spanStore.insert(span);
    }
}
