package ai.core.server.trace.service;

import core.framework.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceUpdate;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.server.trace.web.ingest.IngestRequest;
import ai.core.server.trace.web.ingest.IngestSpanRequest;
import ai.core.server.util.MigrationErrors;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private static final String GEN_AI_AGENT_NAME = "gen_ai.agent.name";
    private static final String GEN_AI_AGENT_ID = "gen_ai.agent.id";
    private static final Set<String> OPERATION_TRACE_NAMES = Set.of("agent.run", "agent.turn", "llm_call.run");

    static String resolveUserId(String authUserId, Map<String, String> attributes) {
        if (authUserId != null && !authUserId.isBlank()) return authUserId;
        return attributes != null ? attributes.get("user.id") : null;
    }

    static String friendlyTraceName(String spanName, String agentName) {
        var cleanAgentName = nonBlank(agentName);
        var cleanSpanName = nonBlank(spanName);
        if (cleanAgentName == null) return cleanSpanName;
        if (cleanSpanName == null || OPERATION_TRACE_NAMES.contains(cleanSpanName)) return cleanAgentName;
        return cleanSpanName;
    }

    private static String attr(Map<String, String> attributes, String key) {
        return attributes != null ? nonBlank(attributes.get(key)) : null;
    }

    private static String nonBlank(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    @Inject
    TraceStore traceStore;
    @Inject
    SpanStore spanStore;
    @Inject
    ModelPricingService modelPricingService;
    @Inject
    TracePayloadStore payloadStore;

    public void ingest(IngestRequest request) {
        ingest(request, null, null);
    }

    // authUserId: when non-null (authenticated endpoint), it OVERRIDES any client-supplied user.id (route B).
    // source: when non-null, stamps Trace.source (e.g. "cli"); legacy anonymous path passes null.
    public void ingest(IngestRequest request, String authUserId, String source) {
        if (request.spans == null || request.spans.isEmpty()) return;

        if (payloadStore != null) {
            var compactor = payloadStore.newCompactor();
            for (var spanReq : request.spans) {
                TraceServiceHelper.stripDuplicatedPayloadAttributes(spanReq.attributes, spanReq.input, spanReq.output);
                spanReq.input = compactor.compact(spanReq.traceId, spanReq.input);
                spanReq.output = compactor.compact(spanReq.traceId, spanReq.output);
            }
        }

        // Prefer root spans for trace metadata initialization (parentSpanId == null).
        Map<String, IngestSpanRequest> rootByTrace = new LinkedHashMap<>();
        for (var spanReq : request.spans) {
            if (spanReq.parentSpanId == null) {
                rootByTrace.put(spanReq.traceId, spanReq);
            }
        }

        // Ensure trace doc exists BEFORE saving any spans so $inc has a target.
        Set<String> ensuredTraces = HashSet.newHashSet(request.spans.size());
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

    private void ensureTrace(String traceId, IngestSpanRequest rootSpan, IngestRequest request, String authUserId, String source) {
        if (traceStore.findByTraceId(traceId) != null) {
            updateTrace(traceId, rootSpan);
            return;
        }

        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.agentName = attr(rootSpan.attributes, GEN_AI_AGENT_NAME);
        trace.agentId = attr(rootSpan.attributes, GEN_AI_AGENT_ID);
        trace.name = friendlyTraceName(rootSpan.name, trace.agentName);
        trace.model = rootSpan.model;
        trace.sessionId = rootSpan.attributes != null ? rootSpan.attributes.get("session.id") : null;
        trace.userId = resolveUserId(authUserId, rootSpan.attributes);
        trace.source = source;
        // Trace-level type from the root span. Without this the frontend falls back to "llm_call" for any
        // trace that has a model (backfilled from an LLM child span), mislabeling agent runs as LLM calls.
        trace.type = mapTraceType(rootSpan.type);
        trace.status = mapTraceStatus(rootSpan.status, rootSpan.attributes);
        // CLI spans carry the failure text in output (no OTLP status message); mirror it into
        // error_message so the trace detail/list surfaces the real error instead of a blank field.
        if (trace.status == TraceStatus.ERROR && rootSpan.output != null) {
            trace.errorMessage = rootSpan.output;
        }
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

        // Race-safe: the store's unique trace id decides the winner. If another ingest created the
        // trace concurrently, fall through to the partial update path.
        if (!traceStore.insertIfAbsent(trace)) {
            updateTrace(traceId, rootSpan);
        }
    }

    private void updateTrace(String traceId, IngestSpanRequest rootSpan) {
        // userId/source attribution is set only at insert (ensureTrace) and never overwritten here,
        // so a later anonymous re-ingest of the same trace cannot blank attribution.
        // Targeted updates instead of a full replace keep concurrent counter increments intact.
        var status = mapTraceStatus(rootSpan.status, rootSpan.attributes);
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.status = status;
        // CLI spans carry the failure text in output (no OTLP status message); mirror it into
        // error_message so the trace detail/list surfaces the real error instead of a blank field.
        if (status == TraceStatus.ERROR && rootSpan.output != null) update.errorMessage = rootSpan.output;
        update.updatedAt = ZonedDateTime.now();
        if (rootSpan.output != null) update.output = rootSpan.output;
        if (rootSpan.input != null) update.input = rootSpan.input;
        if (rootSpan.durationMs > 0) update.durationMs = rootSpan.durationMs;
        var completedAt = toZonedDateTime(rootSpan.completedAtEpochMs);
        if (completedAt != null) update.completedAt = completedAt;
        var agentName = attr(rootSpan.attributes, GEN_AI_AGENT_NAME);
        if (agentName != null) update.agentName = agentName;
        var agentId = attr(rootSpan.attributes, GEN_AI_AGENT_ID);
        if (agentId != null) update.agentId = agentId;
        if (rootSpan.model != null && !rootSpan.model.isBlank()) update.model = rootSpan.model;
        traceStore.update(update);
        if (agentName != null) backfillTraceName(traceId, rootSpan.name, agentName);
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
        applyCost(span, spanReq.costUsd, spanReq.attributes);
        span.durationMs = spanReq.durationMs;
        span.status = mapSpanStatus(spanReq.status, spanReq.attributes);
        // CLI spans carry the failure text in output (no OTLP status message); mirror it into
        // errorMessage so the trace detail shows the real error, not a blank field.
        if (span.status == SpanStatus.ERROR && spanReq.output != null) {
            span.errorMessage = spanReq.output;
        }
        span.attributes = spanReq.attributes;
        // Defensive: drop attribute copies that duplicate the payload fields (clients may mirror
        // the OTLP tracer convention and send both); keeps spans small.
        TraceServiceHelper.stripDuplicatedPayloadAttributes(span);
        span.startedAt = toZonedDateTime(spanReq.startedAtEpochMs);
        span.completedAt = toZonedDateTime(spanReq.completedAtEpochMs);
        span.createdAt = ZonedDateTime.now();

        // Race-free dedup: rely on the unique index on spans.span_id (see SchemaMigrationVTraceIndexes).
        // Only the first concurrent inserter succeeds; the rest catch duplicate-key and skip the $inc,
        // so trace counters cannot be double-counted under OTLP retries or multi-instance ingest.
        try {
            spanStore.insert(span);
        } catch (RuntimeException e) {
            if (MigrationErrors.isDuplicateKey(e)) {
                LOGGER.debug("span {} already ingested, skipping", spanReq.spanId);
                return;
            }
            throw e;
        }

        if (spanReq.model != null && !spanReq.model.isEmpty()) {
            backfillTraceModel(spanReq.traceId, spanReq.model);
        }
        backfillTraceIdentity(spanReq.traceId, spanReq.name, spanReq.attributes);

        // Incrementally roll up this span's tokens/cost onto the parent trace doc.
        // Replaces the previous full re-aggregation pass for O(1) per span instead of O(N).
        incrementTraceTotals(spanReq.traceId, span);
        // NOTE: quota accounting no longer happens here. Span-level user attribution is unreliable
        // (LLM spans carry the tokens but no user.id), so usage is metered synchronously via the
        // ExecutionContext tokenCostCallback wired in SessionContextBuilder / AgentRunBuilder instead.
    }

    private void backfillTraceIdentity(String traceId, String spanName, Map<String, String> attributes) {
        var agentName = attr(attributes, GEN_AI_AGENT_NAME);
        if (agentName != null) {
            var update = new TraceUpdate();
            update.traceId = traceId;
            update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.AGENT_NAME;
            update.valueWhenEmpty = agentName;
            update.updatedAt = ZonedDateTime.now();
            traceStore.update(update);
            backfillTraceName(traceId, spanName, agentName);
        }
        var agentId = attr(attributes, GEN_AI_AGENT_ID);
        if (agentId != null) {
            var update = new TraceUpdate();
            update.traceId = traceId;
            update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.AGENT_ID;
            update.valueWhenEmpty = agentId;
            update.updatedAt = ZonedDateTime.now();
            traceStore.update(update);
        }
    }

    private void backfillTraceName(String traceId, String spanName, String agentName) {
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.NAME;
        update.valueWhenEmpty = friendlyTraceName(spanName, agentName);
        update.namePlaceholders = List.copyOf(OPERATION_TRACE_NAMES);
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
    }

    private void backfillTraceModel(String traceId, String model) {
        // Only update when model is still unset; a conditional update keeps concurrent counter
        // updates intact and avoids churning the document once a model is recorded.
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.MODEL;
        update.valueWhenEmpty = model;
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
    }

    private void incrementTraceTotals(String traceId, Span span) {
        long inputDelta = safeLong(span.inputTokens);
        long outputDelta = safeLong(span.outputTokens);
        long cachedDelta = safeLong(span.cachedTokens);
        double costDelta = span.costUsd != null ? span.costUsd : 0.0;
        long totalDelta = inputDelta + outputDelta;

        var update = new TraceUpdate();
        update.traceId = traceId;
        update.inputTokensDelta = inputDelta;
        update.outputTokensDelta = outputDelta;
        update.totalTokensDelta = totalDelta;
        update.cachedTokensDelta = cachedDelta;
        update.costUsdDelta = costDelta;
        // Always refresh updatedAt so list views show progress between aggregation deltas.
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
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

    private void applyCost(Span span, Double requestCostUsd, Map<String, String> attributes) {
        if (requestCostUsd != null) {
            span.costUsd = requestCostUsd;
            span.costSource = "request";
            return;
        }
        var attrCost = parseDoubleAttr(attributes,
            "gen_ai.usage.cost_usd",
            "gen_ai.usage.cost",
            "langfuse.observation.total_cost");
        var price = modelPricingService.resolve(span.model, span.inputTokens, span.outputTokens, span.cachedTokens, span.startedAt, attrCost);
        span.costUsd = price.costUsd();
        span.costSource = price.source();
        span.pricingModelId = price.modelId();
        span.inputPricePer1MTokens = price.inputPricePer1MTokens();
        span.outputPricePer1MTokens = price.outputPricePer1MTokens();
    }

    private Long parseLongAttr(Map<String, String> attributes, String... keys) {
        if (attributes == null) return null;
        for (var key : keys) {
            var value = attributes.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Long.valueOf(value);
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
                return Double.valueOf(value);
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
