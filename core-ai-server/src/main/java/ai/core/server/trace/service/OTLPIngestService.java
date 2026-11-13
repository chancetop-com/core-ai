package ai.core.server.trace.service;

import ai.core.server.run.AgentRunStore;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceUpdate;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;


import core.framework.inject.Inject;


import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author Xander
 */
public class OTLPIngestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OTLPIngestService.class);
    private static final Set<String> SYSTEM_OPERATIONS = Set.of("memory-consolidator", "memory-compressor", "memory-extractor");

    @Inject
    TraceStore traceStore;
    @Inject
    ai.core.server.session.ChatSessionStore chatSessionStore;
    @Inject
    AgentRunStore agentRunStore;
    @Inject
    SpanStore spanStore;
    @Inject
    ModelPricingService modelPricingService;
    @Inject
    TracePayloadStore payloadStore;

    public void ingest(ExportTraceServiceRequest request) {
        var compactor = payloadStore != null ? payloadStore.newCompactor() : null;
        int spanCount = 0;
        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            var resourceAttrs = OTLPParseHelper.extractAttributes(resourceSpans.getResource().getAttributesList());
            spanCount += processResourceSpans(resourceSpans, resourceAttrs, compactor);
        }
        LOGGER.debug("ingested {} spans via OTLP", spanCount);
    }

    private int processResourceSpans(ResourceSpans resourceSpans, Map<String, String> resourceAttrs,
                                     TracePayloadStore.Compactor compactor) {
        int count = 0;
        for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
            for (io.opentelemetry.proto.trace.v1.Span protoSpan : scopeSpans.getSpansList()) {
                processSpan(protoSpan, resourceAttrs, compactor);
                count++;
            }
        }
        return count;
    }

    private void processSpan(io.opentelemetry.proto.trace.v1.Span protoSpan, Map<String, String> resourceAttrs,
                             TracePayloadStore.Compactor compactor) {
        var spanId = OTLPParseHelper.bytesToHex(protoSpan.getSpanId().toByteArray());
        var parentSpanId = protoSpan.getParentSpanId().isEmpty() ? null : OTLPParseHelper.bytesToHex(protoSpan.getParentSpanId().toByteArray());
        var attrs = OTLPParseHelper.extractAttributes(protoSpan.getAttributesList());
        var traceId = OTLPParseHelper.resolveTraceId(protoSpan, attrs);
        linkAgentRun(traceId, attrs);
        ensureTraceExists(traceId, protoSpan.getName(), TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano()), attrs, resourceAttrs);
        saveSpan(protoSpan, traceId, spanId, parentSpanId, attrs, compactor);
        if (parentSpanId == null) {
            upsertTrace(protoSpan, traceId, attrs, resourceAttrs, compactor);
        }
    }

    private void linkAgentRun(String traceId, Map<String, String> attrs) {
        var runId = attrs.get(OTLPTraceMetadata.CORE_AI_RUN_ID);
        if (runId == null || runId.isBlank()) return;
        agentRunStore.claimTraceId(runId, traceId);
    }

    /**
     * Creates a trace doc the moment its root span starts. The identity a trace is attributed by
     * (user/agent/session/source/type) is carried by the root span only — child spans reach the store as
     * soon as they end, so without this the doc is created by the first LLM call, which has no identity,
     * and the trace stays ownerless and mislabeled until the root span lands at the very end.
     */
    public void traceStarted(String traceId, String spanName, long startEpochMs, Map<String, String> attrs,
                             Map<String, String> resourceAttrs) {
        var resolvedTraceId = OTLPParseHelper.resolveTraceId(traceId, attrs);
        // trace creation and span ingestion run on separate queues, so a fast child span can create the
        // doc first; the root still owns the identity, so fill whatever the first writer left empty.
        if (ensureTraceExists(resolvedTraceId, spanName, startEpochMs, attrs, resourceAttrs)) return;
        TraceIdentity.backfill(traceStore, resolvedTraceId, attrs);
    }

    /** @return true when this call created the doc; false when it already existed (or lost the insert race). */
    private boolean ensureTraceExists(String traceId, String spanName, long startMs,
                                      Map<String, String> attrs, Map<String, String> resourceAttrs) {
        if (traceStore.existsByTraceId(traceId)) return false;

        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.name = IngestService.friendlyTraceName(spanName, attrs.get("gen_ai.agent.name"));
        trace.sessionId = attrs.get("session.id");
        trace.userId = attrs.get("user.id");
        trace.agentName = attrs.get("gen_ai.agent.name");
        trace.agentId = attrs.get("gen_ai.agent.id");
        trace.model = attrs.get("gen_ai.request.model");
        trace.source = resolveSource(attrs, trace.sessionId);
        trace.type = resolveType(trace.source, attrs, resourceAttrs);
        trace.status = TraceStatus.RUNNING;
        trace.input = OTLPParseHelper.resolveInput(attrs);
        trace.metadata = OTLPTraceMetadata.traceMetadata(attrs, resourceAttrs);
        trace.durationMs = 0L;
        trace.startedAt = OTLPParseHelper.toZonedDateTime(startMs);
        trace.createdAt = ZonedDateTime.now();
        trace.updatedAt = ZonedDateTime.now();
        trace.inputTokens = 0L;
        trace.outputTokens = 0L;
        trace.totalTokens = 0L;
        trace.cachedTokens = 0L;
        trace.costUsd = 0.0;
        // race-safe: the unique trace id index means a concurrent ingest (likely another request of
        // the same merged gateway session) already created the trace
        return traceStore.insertIfAbsent(trace);
    }

    private void saveSpan(io.opentelemetry.proto.trace.v1.Span protoSpan,
                          String traceId, String spanId, String parentSpanId,
                          Map<String, String> attrs, TracePayloadStore.Compactor compactor) {
        var toolCallId = attrs.get("tool.call_id");
        if (toolCallId != null && !toolCallId.isEmpty() && spanStore.existsToolCall(traceId, toolCallId)) {
            return;  // the same tool call was already synthesized by an earlier request of this conversation
        }

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
        span.input = OTLPParseHelper.resolveInput(attrs);
        span.output = OTLPParseHelper.resolveOutput(attrs);
        span.durationMs = endMs - startMs;
        span.status = OTLPParseHelper.mapSpanStatus(protoSpan.getStatus().getCode(), attrs);
        span.errorMessage = span.status == SpanStatus.ERROR ? OTLPParseHelper.nonEmpty(protoSpan.getStatus().getMessage()) : null;
        span.toolCallId = toolCallId;
        // own copy: the payload attributes below are stripped from the stored copy only, the caller still
        // needs them to derive the trace input/output from the same span
        span.attributes = new LinkedHashMap<>(attrs);
        span.startedAt = OTLPParseHelper.toZonedDateTime(startMs);
        span.completedAt = OTLPParseHelper.toZonedDateTime(endMs);
        span.createdAt = ZonedDateTime.now();
        span.inputTokens = OTLPParseHelper.parseLongAttr(attrs, "gen_ai.usage.input_tokens");
        span.outputTokens = OTLPParseHelper.parseLongAttr(attrs, "gen_ai.usage.output_tokens");
        span.cachedTokens = OTLPParseHelper.parseLongAttr(attrs,
            "gen_ai.usage.cached_tokens",
            "gen_ai.usage.prompt_tokens_details.cached_tokens",
            "gen_ai.usage.input_tokens_details.cached_tokens",
            "usage.prompt_tokens_details.cached_tokens",
            "prompt_tokens_details.cached_tokens");
        applyCost(span, attrs);
        // The langfuse/gen_ai payload attributes were copied to span.input/output above; drop the
        // duplicate copies so spans stay small (a single long trace can otherwise reach 60MB+).
        TraceServiceHelper.stripDuplicatedPayloadAttributes(span);
        if (compactor != null) {
            span.input = compactor.compact(traceId, span.input);
            span.output = compactor.compact(traceId, span.output);
        }
        spanStore.insert(span);

        // Back-fill model onto trace if not yet set
        if (span.model != null && !span.model.isEmpty()) {
            backfillTraceModel(traceId, span.model);
        }

        // Increment trace token/cost totals atomically instead of reloading all spans
        incrementTraceTokens(traceId, span);
        // NOTE: quota accounting no longer happens here. Span-level user attribution is unreliable
        // (LLM spans carry the tokens but no user.id), so usage is metered synchronously via the
        // ExecutionContext tokenCostCallback wired in SessionContextBuilder / AgentRunBuilder instead.
    }

    private void upsertTrace(io.opentelemetry.proto.trace.v1.Span protoSpan,
                             String traceId, Map<String, String> attrs,
                             Map<String, String> resourceAttrs, TracePayloadStore.Compactor compactor) {
        var existing = traceStore.findByTraceId(traceId);
        if (existing != null) {
            long endMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getEndTimeUnixNano());
            updateExistingTrace(existing, protoSpan, attrs, endMs, compactor);
            return;
        }
        createNewTrace(protoSpan, traceId, attrs, resourceAttrs, compactor);
    }

    private void updateExistingTrace(Trace trace, io.opentelemetry.proto.trace.v1.Span protoSpan,
                                     Map<String, String> attrs, long endMs, TracePayloadStore.Compactor compactor) {
        // Targeted updates instead of a full replace: a merged gateway session trace has many root
        // spans that can be ingested concurrently, and a replace would overwrite the atomic counter
        // increments with a stale snapshot.
        var status = OTLPParseHelper.mapTraceStatus(protoSpan.getStatus().getCode(), attrs);
        var update = new TraceUpdate();
        update.traceId = trace.traceId;
        update.status = status;
        if (status == TraceStatus.ERROR) {
            var message = OTLPParseHelper.nonEmpty(protoSpan.getStatus().getMessage());
            if (message != null) update.errorMessage = message;
            else update.clearErrorMessage = true;
        } else {
            update.clearErrorMessage = true;
        }
        var output = OTLPParseHelper.resolveOutput(attrs);
        if (compactor != null && output != null) output = compactor.compact(trace.traceId, output);
        if (output != null) update.output = output;
        update.durationMs = endMs - TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano());
        update.completedAt = OTLPParseHelper.toZonedDateTime(endMs);
        update.updatedAt = ZonedDateTime.now();
        // Backfill model if this span carries it and trace.model is still empty
        var spanModel = attrs.get("gen_ai.request.model");
        if (spanModel != null && !spanModel.isEmpty() && (trace.model == null || trace.model.isEmpty())) {
            update.model = spanModel;
        }
        // Prefer richer span info: if this span carries session/agent/user attributes,
        // upgrade the trace's identity fields (they may be null when the first-arrived
        // span was an external one without context)
        var hasRichContext = attrs.get("session.id") != null || attrs.get("gen_ai.agent.name") != null;
        if (hasRichContext) {
            upgradeTraceWithSpanInfo(update, trace, protoSpan, attrs, compactor);
        }
        var workflow = OTLPTraceMetadata.workflowMetadata(attrs);
        if (!workflow.isEmpty()) {
            var merged = new LinkedHashMap<String, String>();
            if (trace.metadata != null) merged.putAll(trace.metadata);
            merged.putAll(workflow);
            update.metadata = merged;
        }
        traceStore.update(update);
    }

    private void upgradeTraceWithSpanInfo(TraceUpdate update, Trace trace, io.opentelemetry.proto.trace.v1.Span protoSpan,
                                          Map<String, String> attrs, TracePayloadStore.Compactor compactor) {
        update.writeIdentityNulls = true;
        update.name = IngestService.friendlyTraceName(protoSpan.getName(), attrs.get("gen_ai.agent.name"));
        update.sessionId = attrs.get("session.id");
        update.userId = attrs.get("user.id");
        update.agentName = attrs.get("gen_ai.agent.name");
        update.agentId = attrs.get("gen_ai.agent.id");
        var source = resolveSource(attrs, attrs.get("session.id"));
        update.source = source;
        update.type = resolveType(source, attrs, Map.of());
        // The trace doc is created by whichever span arrives first — usually the first LLM call, whose payload
        // is the full request (messages + tools). The run/turn root span carries the real input (the user
        // message), so it replaces that provisional payload instead of only filling an empty field.
        var input = OTLPParseHelper.resolveInput(attrs);
        if (compactor != null && input != null) input = compactor.compact(trace.traceId, input);
        var agentRootInput = attrs.get("gen_ai.agent.name") != null;
        if (input != null && (agentRootInput || trace.input == null || trace.input.isEmpty())) {
            update.input = input;
        }
    }

    private void createNewTrace(io.opentelemetry.proto.trace.v1.Span protoSpan, String traceId,
                                Map<String, String> attrs, Map<String, String> resourceAttrs,
                                TracePayloadStore.Compactor compactor) {
        long startMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getStartTimeUnixNano());
        long endMs = TimeUnit.NANOSECONDS.toMillis(protoSpan.getEndTimeUnixNano());
        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.name = IngestService.friendlyTraceName(protoSpan.getName(), attrs.get("gen_ai.agent.name"));
        trace.sessionId = attrs.get("session.id");
        trace.userId = attrs.get("user.id");
        trace.agentName = attrs.get("gen_ai.agent.name");
        trace.agentId = attrs.get("gen_ai.agent.id");
        trace.model = attrs.get("gen_ai.request.model");
        trace.source = resolveSource(attrs, trace.sessionId);
        trace.type = resolveType(trace.source, attrs, resourceAttrs);
        trace.status = OTLPParseHelper.mapTraceStatus(protoSpan.getStatus().getCode(), attrs);
        trace.errorMessage = trace.status == TraceStatus.ERROR ? OTLPParseHelper.nonEmpty(protoSpan.getStatus().getMessage()) : null;
        trace.input = OTLPParseHelper.resolveInput(attrs);
        trace.output = OTLPParseHelper.resolveOutput(attrs);
        if (compactor != null) {
            trace.input = compactor.compact(traceId, trace.input);
            trace.output = compactor.compact(traceId, trace.output);
        }
        trace.metadata = OTLPTraceMetadata.traceMetadata(attrs, resourceAttrs);
        trace.durationMs = endMs - startMs;
        trace.startedAt = OTLPParseHelper.toZonedDateTime(startMs);
        trace.completedAt = OTLPParseHelper.toZonedDateTime(endMs);
        trace.createdAt = ZonedDateTime.now();
        trace.updatedAt = ZonedDateTime.now();
        trace.inputTokens = 0L;
        trace.outputTokens = 0L;
        trace.totalTokens = 0L;
        trace.cachedTokens = 0L;
        trace.costUsd = 0.0;
        traceStore.insert(trace);
    }

    private void backfillTraceModel(String traceId, String model) {
        // targeted conditional update instead of read-and-replace: spans are large documents (a single
        // LLM span can exceed 1MB), and loading the whole trace per LLM span was a hot-path read.
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.MODEL;
        update.valueWhenEmpty = model;
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
    }

    private void incrementTraceTokens(String traceId, Span span) {
        long input = span.inputTokens != null ? span.inputTokens : 0;
        long output = span.outputTokens != null ? span.outputTokens : 0;
        long cached = span.cachedTokens != null ? span.cachedTokens : 0;
        double cost = span.costUsd != null ? span.costUsd : 0.0;
        if (input <= 0 && output <= 0 && cached <= 0 && cost <= 0) return;

        var update = new TraceUpdate();
        update.traceId = traceId;
        update.inputTokensDelta = input;
        update.outputTokensDelta = output;
        update.totalTokensDelta = input + output;
        update.cachedTokensDelta = cached;
        update.costUsdDelta = cost;
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
    }

    private SpanType resolveSpanType(Map<String, String> attrs) {
        if (attrs.get("media.type") != null) return SpanType.MEDIA;
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

    @SuppressWarnings("unused")
    private String resolveSource(Map<String, String> attrs, String sessionId) {
        // Highest priority: explicit client.type span attribute (set by server-side wrapper span, e.g. LLM call entry)
        var clientType = attrs.get("client.type");
        if (clientType != null) return clientType;
        // System-internal operations (memory consolidation, compression, extraction) are tagged as "system"
        var operationName = attrs.get("gen_ai.operation.name");
        if (operationName != null && SYSTEM_OPERATIONS.contains(operationName)) return "system";
        // Agent trace with session_id — look up chat_sessions to find the session's source
        if (sessionId != null) {
            var session = chatSessionStore.get(sessionId).orElse(null);
            if (session != null && session.source != null) return session.source;
        }
        // No semantic source available; leave null. The type field (resolveType) carries "llm_call" / "external" so the
        // UI tabs can still classify the trace without overloading the source dimension.
        return null;
    }

    private String resolveType(String source, Map<String, String> attrs, Map<String, String> resourceAttrs) {
        // must precede the llm_call check: a media span also carries gen_ai.request.model
        if (attrs.get("media.type") != null) return "media";
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

    private void applyCost(Span span, Map<String, String> attrs) {
        var attrCost = OTLPParseHelper.parseDoubleAttr(attrs,
            "gen_ai.usage.cost_usd",
            "gen_ai.usage.cost",
            "langfuse.observation.total_cost");
        // Media generations settle their price upstream of the gateway (provider credits, per-second, per-image),
        // so the span carries the authoritative cost and its provenance instead of a token-derived estimate.
        var attrCostSource = attrs.get("gen_ai.usage.cost_source");
        if (attrCostSource != null) {
            span.costUsd = attrCost;
            span.costSource = attrCostSource;
            span.pricingModelId = attrs.get("gen_ai.usage.pricing_model_id");
            return;
        }
        var price = modelPricingService.resolve(span.model, span.inputTokens, span.outputTokens, span.cachedTokens, span.startedAt, attrCost);
        span.costUsd = price.costUsd();
        span.costSource = price.source();
        span.pricingModelId = price.modelId();
        span.inputPricePer1MTokens = price.inputPricePer1MTokens();
        span.outputPricePer1MTokens = price.outputPricePer1MTokens();
    }

}


