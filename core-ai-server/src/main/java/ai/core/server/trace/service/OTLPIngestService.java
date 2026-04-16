package ai.core.server.trace.service;

import com.mongodb.client.model.Filters;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.server.domain.ChatSession;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author Xander
 */
public class OTLPIngestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OTLPIngestService.class);

    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<Span> spanCollection;

    public void ingest(ExportTraceServiceRequest request) {
        int spanCount = 0;
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            var resourceAttrs = extractAttributes(resourceSpans.getResource().getAttributesList());
            spanCount += processResourceSpans(resourceSpans, resourceAttrs);
        }
        LOGGER.debug("ingested {} spans via OTLP", spanCount);
    }

    private int processResourceSpans(ResourceSpans resourceSpans, Map<String, String> resourceAttrs) {
        int count = 0;
        for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
            for (io.opentelemetry.proto.trace.v1.Span protoSpan : scopeSpans.getSpansList()) {
                processSpan(protoSpan, resourceAttrs);
                count++;
            }
        }
        return count;
    }

    private void processSpan(io.opentelemetry.proto.trace.v1.Span protoSpan, Map<String, String> resourceAttrs) {
        var traceId = bytesToHex(protoSpan.getTraceId().toByteArray());
        var spanId = bytesToHex(protoSpan.getSpanId().toByteArray());
        var parentSpanId = protoSpan.getParentSpanId().isEmpty() ? null : bytesToHex(protoSpan.getParentSpanId().toByteArray());
        var attrs = extractAttributes(protoSpan.getAttributesList());
        saveSpan(protoSpan, traceId, spanId, parentSpanId, attrs);
        if (parentSpanId == null) {
            upsertTrace(protoSpan, traceId, attrs, resourceAttrs);
        }
    }

    private void saveSpan(io.opentelemetry.proto.trace.v1.Span protoSpan,
                          String traceId, String spanId, String parentSpanId,
                          Map<String, String> attrs) {
        if (!spanCollection.find(Filters.eq("span_id", spanId)).isEmpty()) return;

        long startMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano());
        long endMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getEndTimeUnixNano());

        var span = new Span();
        span.id = UUID.randomUUID().toString();
        span.traceId = traceId;
        span.spanId = spanId;
        span.parentSpanId = parentSpanId;
        span.name = protoSpan.getName();
        span.type = resolveSpanType(attrs);
        span.model = attrs.get("gen_ai.request.model");
        span.input = resolveInput(attrs);
        span.output = resolveOutput(attrs);
        span.durationMs = endMs - startMs;
        span.status = protoSpan.getStatus().getCode() == Status.StatusCode.STATUS_CODE_ERROR ? SpanStatus.ERROR : SpanStatus.OK;
        span.attributes = attrs;
        span.startedAt = toZonedDateTime(startMs);
        span.completedAt = toZonedDateTime(endMs);
        span.createdAt = ZonedDateTime.now();
        span.inputTokens = parseLongAttr(attrs, "gen_ai.usage.input_tokens");
        span.outputTokens = parseLongAttr(attrs, "gen_ai.usage.output_tokens");
        spanCollection.insert(span);

        // Back-fill model onto trace if not yet set
        if (span.model != null && !span.model.isEmpty()) {
            backfillTraceModel(traceId, span.model);
        }

        // Recalculate trace token totals from all spans
        recalculateTraceTokens(traceId);
    }

    private void upsertTrace(io.opentelemetry.proto.trace.v1.Span protoSpan,
                             String traceId, Map<String, String> attrs,
                             Map<String, String> resourceAttrs) {
        long startMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano());
        long endMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getEndTimeUnixNano());

        var existing = traceCollection.find(Filters.eq("trace_id", traceId));
        if (!existing.isEmpty()) {
            var trace = existing.getFirst();
            trace.status = mapTraceStatus(protoSpan.getStatus().getCode());
            trace.output = resolveOutput(attrs);
            trace.durationMs = endMs - startMs;
            trace.completedAt = toZonedDateTime(endMs);
            trace.updatedAt = ZonedDateTime.now();
            // Backfill model if this span carries it and trace.model is still empty
            var spanModel = attrs.get("gen_ai.request.model");
            if (spanModel != null && (trace.model == null || trace.model.isEmpty())) {
                trace.model = spanModel;
            }
            // Prefer richer span info: if this span carries session/agent/user attributes,
            // upgrade the trace's identity fields (they may be null when the first-arrived
            // span was an external one without context)
            var hasRichContext = attrs.get("session.id") != null || attrs.get("gen_ai.agent.name") != null;
            if (hasRichContext) {
                trace.name = protoSpan.getName();
                trace.sessionId = attrs.get("session.id");
                trace.userId = attrs.get("user.id");
                trace.agentName = attrs.get("gen_ai.agent.name");
                trace.source = resolveSource(attrs, resourceAttrs, trace.sessionId);
                trace.type = resolveType(trace.source, attrs);
                if (trace.input == null || trace.input.isEmpty()) trace.input = resolveInput(attrs);
            }
            traceCollection.replace(trace);
            return;
        }

        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.name = protoSpan.getName();
        trace.sessionId = attrs.get("session.id");
        trace.userId = attrs.get("user.id");
        trace.agentName = attrs.get("gen_ai.agent.name");
        trace.model = attrs.get("gen_ai.request.model");
        trace.source = resolveSource(attrs, resourceAttrs, trace.sessionId);
        trace.type = resolveType(trace.source, attrs);
        trace.status = mapTraceStatus(protoSpan.getStatus().getCode());
        trace.input = resolveInput(attrs);
        trace.output = resolveOutput(attrs);
        trace.metadata = Map.of(
            "service", resourceAttrs.getOrDefault("service.name", "unknown"),
            "version", resourceAttrs.getOrDefault("service.version", "unknown"),
            "environment", resourceAttrs.getOrDefault("deployment.environment", "unknown"));
        trace.durationMs = endMs - startMs;
        trace.startedAt = toZonedDateTime(startMs);
        trace.completedAt = toZonedDateTime(endMs);
        trace.createdAt = ZonedDateTime.now();
        trace.updatedAt = ZonedDateTime.now();
        trace.inputTokens = 0L;
        trace.outputTokens = 0L;
        trace.totalTokens = 0L;

        traceCollection.insert(trace);
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

    private SpanType resolveSpanType(Map<String, String> attrs) {
        var obsType = attrs.get("langfuse.observation.type");
        if (obsType != null) {
            return switch (obsType) {
                case "generation" -> SpanType.LLM;
                case "agent" -> SpanType.AGENT;
                case "tool" -> SpanType.TOOL;
                case "chain" -> SpanType.GROUP;
                default -> SpanType.AGENT;
            };
        }
        var opName = attrs.get("gen_ai.operation.name");
        if ("chat".equals(opName)) return SpanType.LLM;
        if ("tool".equals(opName)) return SpanType.TOOL;
        if ("agent".equals(opName)) return SpanType.AGENT;
        return SpanType.AGENT;
    }

    private String resolveInput(Map<String, String> attrs) {
        var input = attrs.get("gen_ai.prompt");
        if (input != null) return input;
        return attrs.get("langfuse.observation.input");
    }

    private String resolveOutput(Map<String, String> attrs) {
        var output = attrs.get("gen_ai.completion");
        if (output != null) return output;
        return attrs.get("langfuse.observation.output");
    }

    private String resolveSource(Map<String, String> attrs, Map<String, String> resourceAttrs, String sessionId) {
        // Highest priority: explicit client.type span attribute (set by server-side wrapper span, e.g. LLM call entry)
        var clientType = attrs.get("client.type");
        if (clientType != null) return clientType;
        // External service (e.g. litellm proxy)
        var serviceName = resourceAttrs.get("service.name");
        if (serviceName != null && !"core-ai".equals(serviceName)) return "external";
        // Agent trace with session_id — look up chat_sessions to find the session's source
        if (sessionId != null) {
            var session = chatSessionCollection.get(sessionId).orElse(null);
            if (session != null && session.source != null) return session.source;
        }
        return null;
    }

    private String resolveType(String source, Map<String, String> attrs) {
        if (source == null) return "external";
        if (source.startsWith("llm_")) return "llm_call";
        if ("external".equals(source)) return "external";
        if (attrs.get("gen_ai.agent.id") != null || attrs.get("gen_ai.agent.name") != null) return "agent";
        return "agent";  // chat/test/api/a2a/scheduled all imply agent context
    }

    private TraceStatus mapTraceStatus(Status.StatusCode code) {
        if (code == Status.StatusCode.STATUS_CODE_ERROR) return TraceStatus.ERROR;
        if (code == Status.StatusCode.STATUS_CODE_OK) return TraceStatus.COMPLETED;
        return TraceStatus.COMPLETED;
    }

    private Map<String, String> extractAttributes(List<KeyValue> kvList) {
        var map = new LinkedHashMap<String, String>();
        for (KeyValue kv : kvList) {
            map.put(kv.getKey(), anyValueToString(kv.getValue()));
        }
        return map;
    }

    private String anyValueToString(AnyValue value) {
        if (value.hasStringValue()) return value.getStringValue();
        if (value.hasIntValue()) return String.valueOf(value.getIntValue());
        if (value.hasDoubleValue()) return String.valueOf(value.getDoubleValue());
        if (value.hasBoolValue()) return String.valueOf(value.getBoolValue());
        return value.toString();
    }

    private ZonedDateTime toZonedDateTime(long epochMs) {
        if (epochMs <= 0) return null;
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private Long parseLongAttr(Map<String, String> attrs, String key) {
        var value = attrs.get(key);
        return value != null ? Long.parseLong(value) : null;
    }

    private String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
