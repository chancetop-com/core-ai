package ai.core.server.trace.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;

import org.bson.conversions.Bson;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
final class TraceServiceHelper {
    static final int MAX_SPANS_PER_TRACE = 5000;

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static List<Trace> page(List<Trace> traces, int offset, int limit) {
        if (offset >= traces.size()) return List.of();
        var end = Math.min(traces.size(), offset + limit);
        return traces.subList(offset, end);
    }

    static Query sortedTraceQuery(List<Bson> filters, int limit) {
        var query = new Query();
        query.limit = limit;
        query.sort = Sorts.descending("created_at");
        if (!filters.isEmpty()) {
            query.filter = filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
        }
        return query;
    }

    static Pattern compileNamePattern(String name) {
        if (!hasText(name)) return null;
        try {
            return Pattern.compile(name, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException ignored) {
            return Pattern.compile("a^");
        }
    }

    static boolean matchesNamePattern(Trace trace, Pattern namePattern) {
        return namePattern == null || trace.name != null && namePattern.matcher(trace.name).find();
    }

    static void putTrace(Map<String, Trace> matches, Trace trace) {
        var key = hasText(trace.id) ? trace.id : trace.traceId;
        if (!hasText(key)) key = Integer.toHexString(System.identityHashCode(trace));
        matches.putIfAbsent(key, trace);
    }

    static int compareCreatedAtDesc(Trace left, Trace right) {
        if (left.createdAt == null && right.createdAt == null) return safeString(left.id).compareTo(safeString(right.id));
        if (left.createdAt == null) return 1;
        if (right.createdAt == null) return -1;
        var compared = right.createdAt.compareTo(left.createdAt);
        if (compared != 0) return compared;
        return safeString(left.id).compareTo(safeString(right.id));
    }

    static String safeString(String value) {
        return value != null ? value : "";
    }

    static boolean containsIgnoreCase(String value, String needle) {
        if (value == null || needle.isEmpty() || needle.length() > value.length()) return false;
        for (int i = 0; i <= value.length() - needle.length(); i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    // Exact-match clauses for a pasted full ID. Server-generated ids are lowercase, but ingested ids
    // (OTLP session.id attributes) are stored verbatim, so the pasted form is matched as well.
    static List<Bson> fullIdClauses(String q) {
        var id = q.toLowerCase(Locale.ROOT);
        List<Bson> idClauses = new java.util.ArrayList<>();
        idClauses.add(Filters.eq("session_id", id));
        idClauses.add(Filters.eq("user_id", id));
        idClauses.add(Filters.eq("trace_id", id));
        if (!id.equals(q)) {
            idClauses.add(Filters.eq("session_id", q));
            idClauses.add(Filters.eq("user_id", q));
            idClauses.add(Filters.eq("trace_id", q));
        }
        return idClauses;
    }

    static String facetValue(Trace trace, String field) {
        return switch (field) {
            case "model" -> trace.model;
            case "agentName", "agent_name" -> trace.agentName;
            case "source" -> trace.source;
            default -> null;
        };
    }

    static String mongoFieldName(String field) {
        if (field == null) return null;
        return switch (field) {
            case "model" -> "model";
            case "agentName", "agent_name" -> "agent_name";
            case "source" -> "source";
            default -> null;
        };
    }

    static boolean needsEnrichment(Trace trace) {
        var needsTokens = trace.totalTokens == null || trace.totalTokens == 0;
        return needsTokens || trace.cachedTokens == null || trace.costUsd == null;
    }

    static void enrichMetricsInBatch(List<Trace> traces, MongoCollection<Span> spanCollection) {
        var pending = traces.stream()
            .filter(trace -> trace.traceId != null && !trace.traceId.isEmpty() && needsEnrichment(trace))
            .toList();
        if (pending.isEmpty()) return;

        var query = new Query();
        query.filter = Filters.in("trace_id", pending.stream().map(trace -> trace.traceId).toList());
        query.limit = MAX_SPANS_PER_TRACE * pending.size();
        var spansByTrace = spanCollection.find(query).stream()
            .filter(span -> span.traceId != null)
            .collect(Collectors.groupingBy(span -> span.traceId));
        pending.forEach(trace -> enrichMetrics(trace, spansByTrace.getOrDefault(trace.traceId, List.of())));
    }

    static void enrichMetrics(Trace trace, List<Span> spans) {
        var needsTokens = trace.totalTokens == null || trace.totalTokens == 0;
        var needsCachedTokens = trace.cachedTokens == null || needsTokens;
        var needsCost = trace.costUsd == null || needsTokens;
        if (!needsTokens && !needsCachedTokens && !needsCost) return;

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

    static Long parseCachedTokens(Map<String, String> attributes) {
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
                return Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
        return null;
    }

    static long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private TraceServiceHelper() {
    }
}
