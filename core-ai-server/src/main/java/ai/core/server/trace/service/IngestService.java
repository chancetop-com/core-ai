package ai.core.server.trace.service;

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

    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<Span> spanCollection;

    public void ingest(IngestRequest request) {
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
                ensureTrace(spanReq.traceId, representative, request);
            }
        }

        for (var spanReq : request.spans) {
            saveSpan(spanReq);
        }

        LOGGER.debug("ingested {} spans for {} traces", request.spans.size(), ensuredTraces.size());
    }

    private void ensureTrace(String traceId, IngestSpanRequest rootSpan, IngestRequest request) {
        var existing = traceCollection.find(Filters.eq("trace_id", traceId));
        if (!existing.isEmpty()) {
            updateTrace(existing.getFirst(), rootSpan);
            return;
        }

        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.name = rootSpan.name;
        trace.sessionId = rootSpan.attributes != null ? rootSpan.attributes.get("session.id") : null;
        trace.userId = rootSpan.attributes != null ? rootSpan.attributes.get("user.id") : null;
        trace.status = mapTraceStatus(rootSpan.status);
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
        trace.costUsd = 0.0;

        traceCollection.insert(trace);
    }

    private void updateTrace(Trace trace, IngestSpanRequest rootSpan) {
        trace.status = mapTraceStatus(rootSpan.status);
        trace.output = rootSpan.output;
        trace.durationMs = rootSpan.durationMs;
        trace.completedAt = toZonedDateTime(rootSpan.completedAtEpochMs);
        trace.updatedAt = ZonedDateTime.now();
        if (rootSpan.input != null) trace.input = rootSpan.input;
        traceCollection.replace(trace);
    }

    private void saveSpan(IngestSpanRequest spanReq) {
        // Dedup: skip if span already persisted (retried OTLP delivery).
        // Idempotency is critical here because we $inc trace totals only on first insert.
        var existing = spanCollection.find(Filters.eq("span_id", spanReq.spanId));
        if (!existing.isEmpty()) return;

        var span = new Span();
        span.id = UUID.randomUUID().toString();
        span.traceId = spanReq.traceId;
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
        span.status = "ERROR".equals(spanReq.status) ? SpanStatus.ERROR : SpanStatus.OK;
        span.attributes = spanReq.attributes;
        span.startedAt = toZonedDateTime(spanReq.startedAtEpochMs);
        span.completedAt = toZonedDateTime(spanReq.completedAtEpochMs);
        span.createdAt = ZonedDateTime.now();
        spanCollection.insert(span);

        // Back-fill model onto trace if not yet set
        if (spanReq.model != null && !spanReq.model.isEmpty()) {
            backfillTraceModel(spanReq.traceId, spanReq.model);
        }

        // Incrementally roll up this span's tokens/cost onto the parent trace doc.
        // Replaces the previous full re-aggregation pass for O(1) per span instead of O(N).
        incrementTraceTotals(spanReq.traceId, span);
    }

    private void backfillTraceModel(String traceId, String model) {
        var existing = traceCollection.find(Filters.eq("trace_id", traceId));
        if (existing.isEmpty()) return;
        var trace = existing.getFirst();
        if (trace.model == null || trace.model.isEmpty()) {
            trace.model = model;
            trace.updatedAt = ZonedDateTime.now();
            traceCollection.replace(trace);
        }
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

    private TraceStatus mapTraceStatus(String status) {
        if ("ERROR".equals(status)) return TraceStatus.ERROR;
        if ("OK".equals(status)) return TraceStatus.COMPLETED;
        return TraceStatus.RUNNING;
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
