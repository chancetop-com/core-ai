package ai.core.server.trace.service;

import com.mongodb.client.model.Filters;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.LinkedHashMap;
import java.util.Map;
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

        // Group spans by traceId, upsert traces
        Map<String, IngestSpanRequest> rootSpans = new LinkedHashMap<>();
        for (var spanReq : request.spans) {
            if (spanReq.parentSpanId == null) {
                rootSpans.put(spanReq.traceId, spanReq);
            }
        }

        // Ensure trace records exist for each traceId
        for (var entry : rootSpans.entrySet()) {
            ensureTrace(entry.getKey(), entry.getValue(), request);
        }

        // Save all spans
        for (var spanReq : request.spans) {
            saveSpan(spanReq);
            // If this span's trace doesn't have a root, still ensure trace exists
            if (!rootSpans.containsKey(spanReq.traceId)) {
                ensureTrace(spanReq.traceId, spanReq, request);
                rootSpans.put(spanReq.traceId, spanReq);
            }
        }

        LOGGER.debug("ingested {} spans for {} traces", request.spans.size(), rootSpans.size());
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
        // Check if span already exists
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

        // Recalculate trace token totals from all spans
        recalculateTraceTokens(spanReq.traceId);
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

    private void recalculateTraceTokens(String traceId) {
        var existing = traceCollection.find(Filters.eq("trace_id", traceId));
        if (existing.isEmpty()) return;

        var trace = existing.getFirst();
        var spans = spanCollection.find(Filters.eq("trace_id", traceId));

        long totalInput = 0;
        long totalOutput = 0;
        for (var span : spans) {
            if (span.inputTokens != null) totalInput += span.inputTokens;
            if (span.outputTokens != null) totalOutput += span.outputTokens;
        }
        trace.inputTokens = totalInput;
        trace.outputTokens = totalOutput;
        trace.totalTokens = totalInput + totalOutput;
        trace.updatedAt = ZonedDateTime.now();
        traceCollection.replace(trace);
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
