package ai.core.server.trace.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import org.bson.conversions.Bson;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
    private static final String CORE_AI_CANCELLED = "core_ai.cancelled";
    private static final String CORE_AI_RUN_ID = "core_ai.run_id";
    private static final String CORE_AI_WORKFLOW_ID = "core_ai.workflow_id";
    private static final String CORE_AI_WORKFLOW_RUN_ID = "core_ai.workflow_run_id";
    private static final String CORE_AI_WORKFLOW_NODE_ID = "core_ai.workflow_node_id";
    private static final String CORE_AI_WORKFLOW_NODE_TYPE = "core_ai.workflow_node_type";

    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
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
        linkAgentRun(traceId, attrs);
        ensureTraceExists(traceId, protoSpan, attrs, resourceAttrs);
        saveSpan(protoSpan, traceId, spanId, parentSpanId, attrs);
        if (parentSpanId == null) {
            upsertTrace(protoSpan, traceId, attrs, resourceAttrs);
        }
    }

    private void linkAgentRun(String traceId, Map<String, String> attrs) {
        var runId = attrs.get(CORE_AI_RUN_ID);
        if (runId == null || runId.isBlank()) return;
        agentRunCollection.update(
            Filters.and(
                Filters.eq("_id", runId),
                Filters.or(Filters.exists("trace_id", false), Filters.eq("trace_id", null), Filters.eq("trace_id", ""))
            ),
            Updates.set("trace_id", traceId)
        );
    }

    private void ensureTraceExists(String traceId, io.opentelemetry.proto.trace.v1.Span protoSpan,
                                   Map<String, String> attrs, Map<String, String> resourceAttrs) {
        var existing = traceCollection.find(Filters.eq("trace_id", traceId));
        if (!existing.isEmpty()) return;

        long startMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano());
        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.name = IngestService.friendlyTraceName(protoSpan.getName(), attrs.get("gen_ai.agent.name"));
        trace.sessionId = attrs.get("session.id");
        trace.userId = attrs.get("user.id");
        trace.agentName = attrs.get("gen_ai.agent.name");
        trace.agentId = attrs.get("gen_ai.agent.id");
        trace.model = attrs.get("gen_ai.request.model");
        trace.source = resolveSource(attrs, resourceAttrs, trace.sessionId);
        trace.type = resolveType(trace.source, attrs, resourceAttrs);
        trace.status = TraceStatus.RUNNING;
        trace.input = resolveInput(attrs);
        trace.metadata = traceMetadata(attrs, resourceAttrs);
        trace.durationMs = 0L;
        trace.startedAt = toZonedDateTime(startMs);
        trace.createdAt = ZonedDateTime.now();
        trace.updatedAt = ZonedDateTime.now();
        trace.inputTokens = 0L;
        trace.outputTokens = 0L;
        trace.totalTokens = 0L;
        trace.cachedTokens = 0L;
        trace.costUsd = 0.0;
        traceCollection.insert(trace);
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
        span.userId = attrs.get("user.id");
        span.spanId = spanId;
        span.parentSpanId = parentSpanId;
        span.name = protoSpan.getName();
        span.type = resolveSpanType(attrs);
        span.model = attrs.get("gen_ai.request.model");
        span.input = resolveInput(attrs);
        span.output = resolveOutput(attrs);
        span.durationMs = endMs - startMs;
        span.status = mapSpanStatus(protoSpan.getStatus().getCode(), attrs);
        span.errorMessage = span.status == SpanStatus.ERROR ? nonEmpty(protoSpan.getStatus().getMessage()) : null;
        span.attributes = attrs;
        span.startedAt = toZonedDateTime(startMs);
        span.completedAt = toZonedDateTime(endMs);
        span.createdAt = ZonedDateTime.now();
        span.inputTokens = parseLongAttr(attrs, "gen_ai.usage.input_tokens");
        span.outputTokens = parseLongAttr(attrs, "gen_ai.usage.output_tokens");
        span.cachedTokens = parseLongAttr(attrs,
            "gen_ai.usage.cached_tokens",
            "gen_ai.usage.prompt_tokens_details.cached_tokens",
            "gen_ai.usage.input_tokens_details.cached_tokens",
            "usage.prompt_tokens_details.cached_tokens",
            "prompt_tokens_details.cached_tokens");
        span.costUsd = resolveCostUsd(span.model, span.inputTokens, span.outputTokens, span.cachedTokens, attrs);
        spanCollection.insert(span);

        // Back-fill model onto trace if not yet set
        if (span.model != null && !span.model.isEmpty()) {
            backfillTraceModel(traceId, span.model);
        }

        // Increment trace token/cost totals atomically instead of reloading all spans
        incrementTraceTokens(traceId, span);
    }

    private void upsertTrace(io.opentelemetry.proto.trace.v1.Span protoSpan,
                             String traceId, Map<String, String> attrs,
                             Map<String, String> resourceAttrs) {
        long startMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano());
        long endMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getEndTimeUnixNano());

        var existing = traceCollection.find(Filters.eq("trace_id", traceId));
        if (!existing.isEmpty()) {
            updateExistingTrace(existing.getFirst(), protoSpan, attrs, endMs);
            return;
        }
        createNewTrace(protoSpan, traceId, attrs, resourceAttrs, startMs, endMs);
    }

    private void updateExistingTrace(Trace trace, io.opentelemetry.proto.trace.v1.Span protoSpan,
                                    Map<String, String> attrs, long endMs) {
        trace.status = mapTraceStatus(protoSpan.getStatus().getCode(), attrs);
        trace.errorMessage = trace.status == TraceStatus.ERROR ? nonEmpty(protoSpan.getStatus().getMessage()) : null;
        trace.output = resolveOutput(attrs);
        trace.durationMs = endMs - TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano());
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
            upgradeTraceWithSpanInfo(trace, protoSpan, attrs);
        }
        mergeTraceMetadata(trace, workflowMetadata(attrs));
        traceCollection.replace(trace);
    }

    private void upgradeTraceWithSpanInfo(Trace trace, io.opentelemetry.proto.trace.v1.Span protoSpan,
                                          Map<String, String> attrs) {
        trace.name = IngestService.friendlyTraceName(protoSpan.getName(), attrs.get("gen_ai.agent.name"));
        trace.sessionId = attrs.get("session.id");
        trace.userId = attrs.get("user.id");
        trace.agentName = attrs.get("gen_ai.agent.name");
        trace.agentId = attrs.get("gen_ai.agent.id");
        trace.source = resolveSource(attrs, Map.of(), trace.sessionId);
        trace.type = resolveType(trace.source, attrs, Map.of());
        if (trace.input == null || trace.input.isEmpty()) trace.input = resolveInput(attrs);
    }

    private void createNewTrace(io.opentelemetry.proto.trace.v1.Span protoSpan, String traceId,
                               Map<String, String> attrs, Map<String, String> resourceAttrs,
                               long startMs, long endMs) {
        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.name = IngestService.friendlyTraceName(protoSpan.getName(), attrs.get("gen_ai.agent.name"));
        trace.sessionId = attrs.get("session.id");
        trace.userId = attrs.get("user.id");
        trace.agentName = attrs.get("gen_ai.agent.name");
        trace.agentId = attrs.get("gen_ai.agent.id");
        trace.model = attrs.get("gen_ai.request.model");
        trace.source = resolveSource(attrs, resourceAttrs, trace.sessionId);
        trace.type = resolveType(trace.source, attrs, resourceAttrs);
        trace.status = mapTraceStatus(protoSpan.getStatus().getCode(), attrs);
        trace.errorMessage = trace.status == TraceStatus.ERROR ? nonEmpty(protoSpan.getStatus().getMessage()) : null;
        trace.input = resolveInput(attrs);
        trace.output = resolveOutput(attrs);
        trace.metadata = traceMetadata(attrs, resourceAttrs);
        trace.durationMs = endMs - startMs;
        trace.startedAt = toZonedDateTime(startMs);
        trace.completedAt = toZonedDateTime(endMs);
        trace.createdAt = ZonedDateTime.now();
        trace.updatedAt = ZonedDateTime.now();
        trace.inputTokens = 0L;
        trace.outputTokens = 0L;
        trace.totalTokens = 0L;
        trace.cachedTokens = 0L;
        trace.costUsd = 0.0;
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

    private Map<String, String> traceMetadata(Map<String, String> attrs, Map<String, String> resourceAttrs) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("service", resourceAttrs.getOrDefault("service.name", "unknown"));
        metadata.put("version", resourceAttrs.getOrDefault("service.version", "unknown"));
        metadata.put("environment", resourceAttrs.getOrDefault("deployment.environment", "unknown"));
        metadata.putAll(workflowMetadata(attrs));
        return metadata;
    }

    private Map<String, String> workflowMetadata(Map<String, String> attrs) {
        var metadata = new LinkedHashMap<String, String>();
        putAttr(metadata, "agent_run_id", attrs, CORE_AI_RUN_ID);
        putAttr(metadata, "workflow_id", attrs, CORE_AI_WORKFLOW_ID);
        putAttr(metadata, "workflow_run_id", attrs, CORE_AI_WORKFLOW_RUN_ID);
        putAttr(metadata, "workflow_node_id", attrs, CORE_AI_WORKFLOW_NODE_ID);
        putAttr(metadata, "workflow_node_type", attrs, CORE_AI_WORKFLOW_NODE_TYPE);
        return metadata;
    }

    private void putAttr(Map<String, String> target, String targetKey, Map<String, String> attrs, String attrKey) {
        var value = attrs.get(attrKey);
        if (value != null && !value.isBlank()) target.put(targetKey, value);
    }

    private void mergeTraceMetadata(Trace trace, Map<String, String> metadata) {
        if (metadata.isEmpty()) return;
        var merged = new LinkedHashMap<String, String>();
        if (trace.metadata != null) merged.putAll(trace.metadata);
        merged.putAll(metadata);
        trace.metadata = merged;
    }

    private void incrementTraceTokens(String traceId, Span span) {
        var inc = new ArrayList<Bson>();
        long input = span.inputTokens != null ? span.inputTokens : 0;
        long output = span.outputTokens != null ? span.outputTokens : 0;
        if (input > 0) inc.add(Updates.inc("input_tokens", input));
        if (output > 0) inc.add(Updates.inc("output_tokens", output));
        long total = input + output;
        if (total > 0) inc.add(Updates.inc("total_tokens", total));
        if (span.cachedTokens != null && span.cachedTokens > 0) inc.add(Updates.inc("cached_tokens", span.cachedTokens));
        if (span.costUsd != null && span.costUsd > 0) inc.add(Updates.inc("cost_usd", span.costUsd));
        if (!inc.isEmpty()) {
            inc.add(Updates.set("updated_at", ZonedDateTime.now()));
            traceCollection.update(Filters.eq("trace_id", traceId), Updates.combine(inc));
        }
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

    private String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @SuppressWarnings("unused")
    private String resolveSource(Map<String, String> attrs, Map<String, String> resourceAttrs, String sessionId) {
        // Highest priority: explicit client.type span attribute (set by server-side wrapper span, e.g. LLM call entry)
        var clientType = attrs.get("client.type");
        if (clientType != null) return clientType;
        // Agent trace with session_id — look up chat_sessions to find the session's source
        if (sessionId != null) {
            var session = chatSessionCollection.get(sessionId).orElse(null);
            if (session != null && session.source != null) return session.source;
        }
        // No semantic source available; leave null. The type field (resolveType) carries "llm_call" / "external" so the
        // UI tabs can still classify the trace without overloading the source dimension.
        return null;
    }

    private String resolveType(String source, Map<String, String> attrs, Map<String, String> resourceAttrs) {
        if (attrs.get("gen_ai.agent.id") != null || attrs.get("gen_ai.agent.name") != null) return "agent";
        if (isLLMCall(attrs)) return "llm_call";
        var serviceName = resourceAttrs.get("service.name");
        if (serviceName != null && !isInternalService(serviceName)) return "external";
        if (source != null) return "agent";  // chat/a2a/api/scheduled/workflow all imply agent context
        return "external";
    }

    private boolean isLLMCall(Map<String, String> attrs) {
        return attrs.get("gen_ai.request.model") != null
            || "chat".equals(attrs.get("gen_ai.operation.name"))
            || "generation".equals(attrs.get("langfuse.observation.type"));
    }

    private boolean isInternalService(String serviceName) {
        return serviceName == null || serviceName.isBlank() || "core-ai".equals(serviceName) || serviceName.startsWith("core-ai-");
    }

    private SpanStatus mapSpanStatus(Status.StatusCode code, Map<String, String> attrs) {
        if (isCancelled(attrs)) return SpanStatus.CANCELLED;
        if (code == Status.StatusCode.STATUS_CODE_ERROR) return SpanStatus.ERROR;
        return SpanStatus.OK;
    }

    private TraceStatus mapTraceStatus(Status.StatusCode code, Map<String, String> attrs) {
        if (isCancelled(attrs)) return TraceStatus.CANCELLED;
        if (code == Status.StatusCode.STATUS_CODE_ERROR) return TraceStatus.ERROR;
        if (code == Status.StatusCode.STATUS_CODE_OK) return TraceStatus.COMPLETED;
        return TraceStatus.COMPLETED;
    }

    private boolean isCancelled(Map<String, String> attrs) {
        return "true".equalsIgnoreCase(attrs.get(CORE_AI_CANCELLED));
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

    private Double resolveCostUsd(String model, Long inputTokens, Long outputTokens, Long cachedTokens, Map<String, String> attrs) {
        var attrCost = parseDoubleAttr(attrs,
            "gen_ai.usage.cost_usd",
            "gen_ai.usage.cost",
            "langfuse.observation.total_cost");
        if (attrCost != null) return attrCost;
        return LLMModelContextRegistry.getInstance().estimateCostUsd(model,
            safeLong(inputTokens), safeLong(outputTokens), safeLong(cachedTokens));
    }

    private Long parseLongAttr(Map<String, String> attrs, String... keys) {
        for (var key : keys) {
            var value = attrs.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                LOGGER.debug("invalid long trace attribute {}={}", key, value);
            }
        }
        return null;
    }

    private Double parseDoubleAttr(Map<String, String> attrs, String... keys) {
        for (var key : keys) {
            var value = attrs.get(key);
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

    private String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
