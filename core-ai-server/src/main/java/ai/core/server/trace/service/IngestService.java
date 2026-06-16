package ai.core.server.trace.service;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.server.trace.web.ingest.IngestRequest;
import ai.core.server.trace.web.ingest.IngestSpanRequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author Xander
 */
public class IngestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestService.class);
    private static final String CORE_AI_CANCELLED = "core_ai.cancelled";

    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<Span> spanCollection;

    public void ingest(IngestRequest request) {
        ingest(request, null, null);
    }

    // authUserId: when non-null (authenticated endpoint), it OVERRIDES any client-supplied user.id (route B).
    // source: when non-null, stamps Trace.source (e.g. "cli"); legacy anonymous path passes null.
    public void ingest(IngestRequest request, String authUserId, String source) {
        if (request.spans == null || request.spans.isEmpty()) return;

        // Prefer root spans for trace metadata initialization (parentSpanId == null).
        Map<String, IngestSpanRequest> rootByTrace = new LinkedHashMap<>();
        for (var spanReq : request.spans) {
            if (spanReq.parentSpanId == null) {
                rootByTrace.put(spanReq.traceId, spanReq);
            }
        }

        // Ensure trace doc exists BEFORE saving any spans so $inc has a target.
        Set<String> ensuredTraces = new HashSet<>();
        for (var spanReq : request.spans) {
            if (ensuredTraces.add(spanReq.traceId)) {
                var representative = rootByTrace.getOrDefault(spanReq.traceId, spanReq);
                ensureTrace(spanReq.traceId, representative, request, authUserId, source);
            }
        }

        for (var spanReq : request.spans) {
            saveSpan(spanReq, authUserId);
        }

        LOGGER.debug("ingested {} spans for {} traces", request.spans.size(), ensuredTraces.size());
    }

    static String resolveUserId(String authUserId, Map<String, String> attributes) {
        if (authUserId != null && !authUserId.isBlank()) return authUserId;
        return attributes != null ? attributes.get("user.id") : null;
    }

    private void ensureTrace(String traceId, IngestSpanRequest rootSpan, IngestRequest request, String authUserId, String source) {
        var existing = traceCollection.find(Filters.eq("trace_id", traceId));
        if (!existing.isEmpty()) {
            updateTrace(traceId, rootSpan);
            return;
        }

        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.name = rootSpan.name;
        trace.sessionId = rootSpan.attributes != null ? rootSpan.attributes.get("session.id") : null;
        trace.userId = resolveUserId(authUserId, rootSpan.attributes);
        trace.source = source;
        // Trace-level type from the root span. Without this the frontend falls back to "llm_call" for any
        // trace that has a model (backfilled from an LLM child span), mislabeling agent runs as LLM calls.
        trace.type = mapTraceType(rootSpan.type);
        trace.status = mapTraceStatus(rootSpan.status, rootSpan.attributes);
        trace.input = rootSpan.input;
        trace.output = rootSpan.output;
        trace.metadata = Map.of("service", request.serviceName != null ? request.serviceName : "unknown",
            "version", request.serviceVersion != null ? request.serviceVersion : "unknown",
            "environment", request.environment != null ? request.environment : "unknown");
        trace.durationMs = rootSpan.durationMs;
        trace.startedAt = toZonedDateTime(rootSpan.startedAtEpochMs);
        trace.completedAt = toZonedDateTime(rootSpan.completedAtEpochMs);
        trace.createdAt = ZonedDateTime.now();
        trace.updatedAt = ZonedDateTime.now();

        trace.inputTokens = 0L;
        trace.outputTokens = 0L;
        trace.totalTokens = 0L;
        trace.cachedTokens = 0L;
        // costUsd must start as numeric 0.0, NOT null: MongoDB $inc fails on an existing null field
        // ("Cannot apply $inc to a value of non-numeric type"), which would 400 every ingest carrying a
        // priced LLM span. $inc treats a MISSING field as 0, but the codec writes null as a present field.
        trace.costUsd = 0.0;

        // Race-safe: rely on the unique index on traces.trace_id. If another ingest created
        // the trace concurrently, fall through to the $set update path.
        try {
            traceCollection.insert(trace);
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) {
                updateTrace(traceId, rootSpan);
                return;
            }
            throw e;
        }
    }

    private void updateTrace(String traceId, IngestSpanRequest rootSpan) {
        // First-write-wins on identity: userId/source are set only at insert (ensureTrace) and never
        // overwritten here, so a later anonymous re-ingest of the same trace cannot blank attribution.
        // Use targeted $set updates instead of full document replace so concurrent $inc
        // operations on token/cost counters aren't overwritten with a stale snapshot.
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.set("status", mapTraceStatus(rootSpan.status, rootSpan.attributes)));
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));
        if (rootSpan.output != null) updates.add(Updates.set("output", rootSpan.output));
        if (rootSpan.input != null) updates.add(Updates.set("input", rootSpan.input));
        if (rootSpan.durationMs > 0) updates.add(Updates.set("duration_ms", rootSpan.durationMs));
        var completedAt = toZonedDateTime(rootSpan.completedAtEpochMs);
        if (completedAt != null) updates.add(Updates.set("completed_at", completedAt));
        traceCollection.update(Filters.eq("trace_id", traceId), Updates.combine(updates));
    }

    private void saveSpan(IngestSpanRequest spanReq, String authUserId) {
        var span = new Span();
        span.id = UUID.randomUUID().toString();
        span.traceId = spanReq.traceId;
        span.userId = resolveUserId(authUserId, spanReq.attributes);
        span.spanId = spanReq.spanId;
        span.parentSpanId = spanReq.parentSpanId;
        span.name = spanReq.name;
        span.type = mapSpanType(spanReq.type);
        span.model = spanReq.model;
        span.input = spanReq.input;
        span.output = spanReq.output;
        span.inputTokens = spanReq.inputTokens;
        span.outputTokens = spanReq.outputTokens;
        span.cachedTokens = resolveCachedTokens(spanReq);
        span.costUsd = resolveCostUsd(spanReq.model, span.inputTokens, span.outputTokens, span.cachedTokens,
            spanReq.costUsd, spanReq.attributes);
        span.durationMs = spanReq.durationMs;
        span.status = mapSpanStatus(spanReq.status, spanReq.attributes);
        span.attributes = spanReq.attributes;
        span.startedAt = toZonedDateTime(spanReq.startedAtEpochMs);
        span.completedAt = toZonedDateTime(spanReq.completedAtEpochMs);
        span.createdAt = ZonedDateTime.now();

        // Race-free dedup: rely on the unique index on spans.span_id (see SchemaMigrationVTraceIndexes).
        // Only the first concurrent inserter succeeds; the rest catch duplicate-key and skip the $inc,
        // so trace counters cannot be double-counted under OTLP retries or multi-instance ingest.
        try {
            spanCollection.insert(span);
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) {
                LOGGER.debug("span {} already ingested, skipping", spanReq.spanId);
                return;
            }
            throw e;
        }

        if (spanReq.model != null && !spanReq.model.isEmpty()) {
            backfillTraceModel(spanReq.traceId, spanReq.model);
        }

        // Incrementally roll up this span's tokens/cost onto the parent trace doc.
        // Replaces the previous full re-aggregation pass for O(1) per span instead of O(N).
        incrementTraceTotals(spanReq.traceId, span);
    }

    private void backfillTraceModel(String traceId, String model) {
        // Only update when model is still unset; use conditional filter so concurrent counter
        // updates don't get overwritten and we don't churn the doc once a model is recorded.
        var filter = Filters.and(
            Filters.eq("trace_id", traceId),
            Filters.or(Filters.exists("model", false), Filters.eq("model", null), Filters.eq("model", ""))
        );
        traceCollection.update(filter, Updates.combine(
            Updates.set("model", model),
            Updates.set("updated_at", ZonedDateTime.now())
        ));
    }

    private void incrementTraceTotals(String traceId, Span span) {
        long inputDelta = safeLong(span.inputTokens);
        long outputDelta = safeLong(span.outputTokens);
        long cachedDelta = safeLong(span.cachedTokens);
        double costDelta = span.costUsd != null ? span.costUsd : 0.0;
        long totalDelta = inputDelta + outputDelta;

        List<Bson> updates = new ArrayList<>();
        if (inputDelta != 0) updates.add(Updates.inc("input_tokens", inputDelta));
        if (outputDelta != 0) updates.add(Updates.inc("output_tokens", outputDelta));
        if (totalDelta != 0) updates.add(Updates.inc("total_tokens", totalDelta));
        if (cachedDelta != 0) updates.add(Updates.inc("cached_tokens", cachedDelta));
        if (costDelta != 0.0) updates.add(Updates.inc("cost_usd", costDelta));
        // Always refresh updatedAt so list views show progress between aggregation deltas.
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));

        traceCollection.update(Filters.eq("trace_id", traceId), Updates.combine(updates));
    }

    private Long resolveCachedTokens(IngestSpanRequest spanReq) {
        if (spanReq.cachedTokens != null) return spanReq.cachedTokens;
        return parseLongAttr(spanReq.attributes,
            "gen_ai.usage.cached_tokens",
            "gen_ai.usage.prompt_tokens_details.cached_tokens",
            "gen_ai.usage.input_tokens_details.cached_tokens",
            "usage.prompt_tokens_details.cached_tokens",
            "prompt_tokens_details.cached_tokens");
    }

    private Double resolveCostUsd(String model, Long inputTokens, Long outputTokens, Long cachedTokens,
                                  Double requestCostUsd, Map<String, String> attributes) {
        if (requestCostUsd != null) return requestCostUsd;
        var attrCost = parseDoubleAttr(attributes,
            "gen_ai.usage.cost_usd",
            "gen_ai.usage.cost",
            "langfuse.observation.total_cost");
        if (attrCost != null) return attrCost;
        return LLMModelContextRegistry.getInstance().estimateCostUsd(model,
            safeLong(inputTokens), safeLong(outputTokens), safeLong(cachedTokens));
    }

    private Long parseLongAttr(Map<String, String> attributes, String... keys) {
        if (attributes == null) return null;
        for (var key : keys) {
            var value = attributes.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                LOGGER.debug("invalid long trace attribute {}={}", key, value);
            }
        }
        return null;
    }

    private Double parseDoubleAttr(Map<String, String> attributes, String... keys) {
        if (attributes == null) return null;
        for (var key : keys) {
            var value = attributes.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                LOGGER.debug("invalid double trace attribute {}={}", key, value);
            }
        }
        return null;
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private SpanStatus mapSpanStatus(String status, Map<String, String> attributes) {
        if (isCancelled(status, attributes)) return SpanStatus.CANCELLED;
        if ("ERROR".equals(status)) return SpanStatus.ERROR;
        return SpanStatus.OK;
    }

    private TraceStatus mapTraceStatus(String status, Map<String, String> attributes) {
        if (isCancelled(status, attributes)) return TraceStatus.CANCELLED;
        if ("ERROR".equals(status)) return TraceStatus.ERROR;
        if ("OK".equals(status)) return TraceStatus.COMPLETED;
        return TraceStatus.RUNNING;
    }

    // Trace-level type (agent | llm_call | external) derived from the root span. An LLM-rooted trace is an
    // llm_call; agent/flow/group roots (and unknowns) are agent runs.
    private String mapTraceType(String rootSpanType) {
        if ("LLM".equals(rootSpanType)) return "llm_call";
        return "agent";
    }

    private boolean isCancelled(String status, Map<String, String> attributes) {
        return "CANCELLED".equals(status)
            || attributes != null && "true".equalsIgnoreCase(attributes.get(CORE_AI_CANCELLED));
    }

    private SpanType mapSpanType(String type) {
        if (type == null) return SpanType.AGENT;
        return switch (type) {
            case "LLM" -> SpanType.LLM;
            case "TOOL" -> SpanType.TOOL;
            case "FLOW" -> SpanType.FLOW;
            case "GROUP" -> SpanType.GROUP;
            default -> SpanType.AGENT;
        };
    }

    private ZonedDateTime toZonedDateTime(long epochMs) {
        if (epochMs <= 0) return null;
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }
}
