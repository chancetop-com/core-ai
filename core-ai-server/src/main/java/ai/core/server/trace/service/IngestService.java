package ai.core.server.trace.service;

import core.framework.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.server.trace.TraceAttribute;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceUpdate;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.server.trace.web.ingest.IngestRequest;
import ai.core.server.trace.web.ingest.IngestSpanRequest;
import ai.core.server.trace.web.ingest.IngestStartRequest;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author Xander
 */
public class IngestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestService.class);
    private static final Set<String> OPERATION_TRACE_NAMES = Set.of("agent.run", "agent.turn", "llm_call.run");

    static String resolveUserId(String authUserId, Map<String, String> attributes) {
        if (authUserId != null && !authUserId.isBlank()) return authUserId;
        return attributes != null ? attributes.get("user.id") : null;
    }

    static String friendlyTraceName(String spanName, String agentName) {
        var cleanAgentName = IngestTraceHelper.nonBlank(agentName);
        var cleanSpanName = IngestTraceHelper.nonBlank(spanName);
        if (cleanAgentName == null) return cleanSpanName;
        if (cleanSpanName == null || OPERATION_TRACE_NAMES.contains(cleanSpanName)) return cleanAgentName;
        return cleanSpanName;
    }

    @Inject
    TraceStore traceStore;
    @Inject
    SpanStore spanStore;
    @Inject
    ModelPricingService modelPricingService;
    @Inject
    TracePayloadStore payloadStore;
    @Inject
    TraceBodyStore bodyStore;

    public void startTrace(IngestStartRequest request, String authUserId, String source) {
        if (request == null || request.traceId == null || request.traceId.isBlank()) return;
        var now = ZonedDateTime.now();
        var trace = IngestTraceHelper.buildStartTrace(request, authUserId, source, now);
        bodyStore.offload(trace);
        if (traceStore.insertIfAbsent(trace)) {
            LOGGER.debug("trace started, traceId={}, source={}", request.traceId, source);
            return;
        }
        handleStartConflict(request.traceId, trace.name, authUserId, now);
    }

    private void handleStartConflict(String traceId, String resolvedName, String authUserId, ZonedDateTime now) {
        var existing = traceStore.findByTraceId(traceId);
        if (existing == null) return;
        if (IngestTraceHelper.isOwnerMismatch(existing, authUserId)) {
            LOGGER.warn("trace start rejected: owner mismatch, traceId={}, authUserId={}, ownerUserId={}",
                traceId, authUserId, existing.userId);
            return;
        }
        if (IngestTraceHelper.nonBlank(existing.name) != null) {
            LOGGER.debug("trace start ignored, trace already exists, traceId={}", traceId);
            return;
        }
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.NAME;
        update.valueWhenEmpty = resolvedName;
        update.updatedAt = now;
        traceStore.update(update);
        LOGGER.debug("trace start hit existing row, backfilled name, traceId={}", traceId);
    }

    public void ingest(IngestRequest request) {
        ingest(request, null, null);
    }

    // authUserId: when non-null (authenticated endpoint), it OVERRIDES any client-supplied user.id (route B).
    // source: when non-null, stamps Trace.source (e.g. "cli"); legacy anonymous path passes null.
    public void ingest(IngestRequest request, String authUserId, String source) {
        if (request.spans == null || request.spans.isEmpty()) return;

        var authenticatedUser = IngestTraceHelper.nonBlank(authUserId);
        Map<String, Trace> existingByTrace = authenticatedUser != null ? new LinkedHashMap<>() : null;
        Set<String> rejectedTraces = new HashSet<>();
        if (authenticatedUser != null) {
            checkTraceOwnership(request, authenticatedUser, existingByTrace, rejectedTraces);
        }

        List<IngestSpanRequest> acceptedSpans = rejectedTraces.isEmpty() ? request.spans
            : request.spans.stream().filter(spanReq -> !rejectedTraces.contains(spanReq.traceId)).toList();
        if (acceptedSpans.isEmpty()) return;

        var newSpans = filterExistingSpans(acceptedSpans);
        storePayloads(request, rejectedTraces);
        compactSpans(newSpans);

        var ensuredTraces = ensureTraces(newSpans, acceptedSpans, existingByTrace, request, authUserId, source);
        for (var spanReq : newSpans) {
            saveSpan(spanReq, authUserId);
        }
        recomputeTotals(ensuredTraces);

        LOGGER.debug("ingested {} spans for {} traces", newSpans.size(), ensuredTraces.size());
    }

    private void checkTraceOwnership(IngestRequest request, String authUserId,
                                     Map<String, Trace> existingByTrace, Set<String> rejectedTraces) {
        var traceIds = new LinkedHashSet<String>();
        for (var spanReq : request.spans) {
            if (spanReq != null && spanReq.traceId != null) traceIds.add(spanReq.traceId);
        }
        if (request.payloads != null) {
            for (var payload : request.payloads) {
                if (payload != null && payload.traceId != null) traceIds.add(payload.traceId);
            }
        }
        for (var traceId : traceIds) {
            var existing = traceStore.findByTraceId(traceId);
            existingByTrace.put(traceId, existing);
            if (existing != null && IngestTraceHelper.isOwnerMismatch(existing, authUserId)) {
                rejectedTraces.add(traceId);
                LOGGER.warn("trace ingest rejected: owner mismatch, traceId={}, authUserId={}, ownerUserId={}",
                    traceId, authUserId, existing.userId);
            }
        }
    }

    private List<IngestSpanRequest> filterExistingSpans(List<IngestSpanRequest> spans) {
        var spanIds = new LinkedHashSet<String>();
        for (var spanReq : spans) {
            if (spanReq.spanId != null && !spanReq.spanId.isBlank()) spanIds.add(spanReq.spanId);
        }
        if (spanIds.isEmpty()) return spans;
        var existingIds = spanStore.existingSpanIds(List.copyOf(spanIds));
        if (existingIds == null || existingIds.isEmpty()) return spans;
        var remaining = spans.stream()
            .filter(spanReq -> spanReq.spanId == null || !existingIds.contains(spanReq.spanId))
            .toList();
        int skipped = spans.size() - remaining.size();
        if (skipped > 0) LOGGER.debug("skipped {} already-ingested spans before compaction/offload", skipped);
        return remaining;
    }

    private void storePayloads(IngestRequest request, Set<String> rejectedTraces) {
        if (payloadStore == null || request.payloads == null) return;
        for (var payload : request.payloads) {
            if (payload == null || payload.traceId == null || payload.dataUrl == null) continue;
            if (rejectedTraces.contains(payload.traceId)) continue;
            if (payloadStore.storePayload(payload.traceId, payload.dataUrl) == null) {
                LOGGER.warn("trace image payload rejected, traceId={}", payload.traceId);
            }
        }
    }

    private void compactSpans(List<IngestSpanRequest> spans) {
        if (payloadStore == null || spans.isEmpty()) return;
        var compactor = payloadStore.newCompactor();
        for (var spanReq : spans) {
            TraceServiceHelper.stripDuplicatedPayloadAttributes(spanReq.attributes, spanReq.input, spanReq.output);
            spanReq.input = compactor.compact(spanReq.traceId, spanReq.input);
            spanReq.output = compactor.compact(spanReq.traceId, spanReq.output);
        }
    }

    private Set<String> ensureTraces(List<IngestSpanRequest> newSpans, List<IngestSpanRequest> acceptedSpans,
                                     Map<String, Trace> existingByTrace, IngestRequest request,
                                     String authUserId, String source) {
        Map<String, IngestSpanRequest> rootByTrace = new LinkedHashMap<>();
        for (var spanReq : newSpans) {
            if (spanReq.parentSpanId == null) rootByTrace.put(spanReq.traceId, spanReq);
        }

        Set<String> ensuredTraces = HashSet.newHashSet(acceptedSpans.size());
        for (var spanReq : newSpans) {
            if (ensuredTraces.add(spanReq.traceId)) {
                var representative = rootByTrace.getOrDefault(spanReq.traceId, spanReq);
                var existing = existingByTrace != null ? existingByTrace.get(spanReq.traceId) : traceStore.findByTraceId(spanReq.traceId);
                ensureTrace(spanReq.traceId, representative, existing, request, authUserId, source);
            }
        }
        for (var spanReq : acceptedSpans) {
            ensuredTraces.add(spanReq.traceId);
        }
        return ensuredTraces;
    }

    private void recomputeTotals(Set<String> traceIds) {
        TraceTotalsReconciler.recompute(spanStore, traceStore, traceIds);
    }

    private void ensureTrace(String traceId, IngestSpanRequest representative, Trace existing,
                             IngestRequest request, String authUserId, String source) {
        boolean hasRoot = representative.parentSpanId == null;
        boolean authenticated = source != null;
        boolean canUpdate = !authenticated || hasRoot;

        if (existing != null) {
            if (canUpdate) updateTrace(traceId, representative, existing);
            return;
        }

        boolean provisional = authenticated && !hasRoot;
        var trace = IngestTraceHelper.buildTrace(traceId, representative, request, authUserId, source, provisional);
        if (!provisional) bodyStore.offload(trace);

        boolean inserted = traceStore.insertIfAbsent(trace);
        if (!inserted && canUpdate) {
            handleEnsureConflict(traceId, representative, authUserId, authenticated);
        }
    }

    private void handleEnsureConflict(String traceId, IngestSpanRequest representative,
                                      String authUserId, boolean authenticated) {
        var current = traceStore.findByTraceId(traceId);
        if (authenticated && current != null && IngestTraceHelper.isOwnerMismatch(current, authUserId)) {
            LOGGER.warn("trace ingest update rejected after race: owner mismatch, traceId={}, authUserId={}, ownerUserId={}",
                traceId, authUserId, current.userId);
            return;
        }
        updateTrace(traceId, representative, current);
    }

    private void updateTrace(String traceId, IngestSpanRequest rootSpan, Trace existing) {
        var status = IngestTraceHelper.mapTraceStatus(rootSpan.status, rootSpan.attributes);
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.status = status;
        if (status == TraceStatus.ERROR && rootSpan.output != null) {
            update.errorMessage = rootSpan.output;
        } else if (status != TraceStatus.ERROR && rootSpan.parentSpanId == null) {
            update.clearErrorMessage = true;
        }
        update.updatedAt = ZonedDateTime.now();
        if (rootSpan.output != null && (existing == null || !TraceBodyStore.sameContent(existing.output, rootSpan.output))) {
            update.output = bodyStore.offload(traceId, null, "out", rootSpan.output);
        }
        if (rootSpan.input != null && (existing == null || !TraceBodyStore.sameContent(existing.input, rootSpan.input))) {
            update.input = bodyStore.offload(traceId, null, "in", rootSpan.input);
        }
        if (rootSpan.durationMs > 0) update.durationMs = rootSpan.durationMs;
        var completedAt = IngestTraceHelper.toZonedDateTime(rootSpan.completedAtEpochMs);
        if (completedAt != null) update.completedAt = completedAt;
        var agentName = IngestTraceHelper.attr(rootSpan.attributes, IngestTraceHelper.GEN_AI_AGENT_NAME);
        if (agentName != null) update.agentName = agentName;
        var agentId = IngestTraceHelper.attr(rootSpan.attributes, IngestTraceHelper.GEN_AI_AGENT_ID);
        if (agentId != null) update.agentId = agentId;
        if (rootSpan.model != null && !rootSpan.model.isBlank()) update.model = rootSpan.model;
        traceStore.update(update);
        TraceIdentity.backfillProvider(traceStore, traceId, IngestTraceHelper.attr(rootSpan.attributes, TraceAttribute.GATEWAY_PROVIDER_ID));
        if (rootSpan.parentSpanId == null || agentName != null) {
            backfillTraceName(traceId, rootSpan.name, agentName);
        }
    }

    private void saveSpan(IngestSpanRequest spanReq, String authUserId) {
        var span = new Span();
        span.id = UUID.randomUUID().toString();
        span.traceId = spanReq.traceId;
        span.userId = resolveUserId(authUserId, spanReq.attributes);
        span.spanId = spanReq.spanId;
        span.parentSpanId = spanReq.parentSpanId;
        span.name = spanReq.name;
        span.type = IngestTraceHelper.mapSpanType(spanReq.type);
        span.model = spanReq.model;
        span.resolvedModel = IngestTraceHelper.resolveResolvedModel(spanReq);
        span.providerId = IngestTraceHelper.attr(spanReq.attributes, TraceAttribute.GATEWAY_PROVIDER_ID);
        span.input = spanReq.input;
        span.output = spanReq.output;
        span.inputTokens = spanReq.inputTokens;
        span.outputTokens = spanReq.outputTokens;
        span.cachedTokens = IngestTraceHelper.resolveCachedTokens(spanReq);
        span.reasoningTokens = TraceServiceHelper.resolveReasoningTokens(spanReq);
        applyCost(span, spanReq.costUsd, spanReq.attributes);
        span.ttftMs = IngestTraceHelper.resolveTtftMs(spanReq);
        span.firstContentMs = TraceServiceHelper.resolveFirstContentMs(spanReq);
        span.durationMs = spanReq.durationMs;
        span.status = IngestTraceHelper.mapSpanStatus(spanReq.status, spanReq.attributes);
        if (span.status == SpanStatus.ERROR && spanReq.output != null) span.errorMessage = spanReq.output;
        span.attributes = spanReq.attributes;
        TraceServiceHelper.stripDuplicatedPayloadAttributes(span);
        bodyStore.offload(span);
        span.startedAt = IngestTraceHelper.toZonedDateTime(spanReq.startedAtEpochMs);
        span.completedAt = IngestTraceHelper.toZonedDateTime(spanReq.completedAtEpochMs);
        span.createdAt = ZonedDateTime.now();

        if (!spanStore.insertIfAbsent(span)) {
            LOGGER.debug("span {} already ingested, skipping", spanReq.spanId);
            return;
        }
        backfillAfterSpanInsert(spanReq, span);
        incrementTraceTotals(spanReq.traceId, span);
    }

    private void backfillAfterSpanInsert(IngestSpanRequest spanReq, Span span) {
        if (spanReq.model != null && !spanReq.model.isEmpty()) backfillTraceModel(spanReq.traceId, spanReq.model);
        TraceIdentity.backfillProvider(traceStore, spanReq.traceId, span.providerId);
        TraceIdentity.backfillResolvedModel(traceStore, spanReq.traceId, span.resolvedModel);
        backfillTraceIdentity(spanReq.traceId, spanReq.name, spanReq.attributes);
    }

    private void backfillTraceIdentity(String traceId, String spanName, Map<String, String> attributes) {
        var agentName = IngestTraceHelper.attr(attributes, IngestTraceHelper.GEN_AI_AGENT_NAME);
        if (agentName != null) {
            var update = new TraceUpdate();
            update.traceId = traceId;
            update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.AGENT_NAME;
            update.valueWhenEmpty = agentName;
            update.updatedAt = ZonedDateTime.now();
            traceStore.update(update);
            backfillTraceName(traceId, spanName, agentName);
        }
        var agentId = IngestTraceHelper.attr(attributes, IngestTraceHelper.GEN_AI_AGENT_ID);
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
        var candidate = friendlyTraceName(spanName, agentName);
        if (candidate == null) return;
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.NAME;
        update.valueWhenEmpty = candidate;
        update.namePlaceholders = List.copyOf(OPERATION_TRACE_NAMES);
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
    }

    private void backfillTraceModel(String traceId, String model) {
        var update = new TraceUpdate();
        update.traceId = traceId;
        update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.MODEL;
        update.valueWhenEmpty = model;
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
    }

    private void incrementTraceTotals(String traceId, Span span) {
        long inputDelta = TraceServiceHelper.safeLong(span.inputTokens);
        long outputDelta = TraceServiceHelper.safeLong(span.outputTokens);
        long cachedDelta = TraceServiceHelper.safeLong(span.cachedTokens);
        double costDelta = span.costUsd != null ? span.costUsd : 0.0;
        long totalDelta = inputDelta + outputDelta;

        var update = new TraceUpdate();
        update.traceId = traceId;
        update.inputTokensDelta = inputDelta;
        update.outputTokensDelta = outputDelta;
        update.totalTokensDelta = totalDelta;
        update.cachedTokensDelta = cachedDelta;
        update.costUsdDelta = costDelta;
        update.updatedAt = ZonedDateTime.now();
        traceStore.update(update);
    }

    private void applyCost(Span span, Double requestCostUsd, Map<String, String> attributes) {
        if (requestCostUsd != null) {
            span.costUsd = requestCostUsd;
            span.costSource = "request";
            return;
        }
        var attrCost = IngestTraceHelper.parseDoubleAttr(attributes,
            "gen_ai.usage.cost_usd",
            "gen_ai.usage.cost",
            "langfuse.observation.total_cost");
        var price = modelPricingService.resolve(span.model, span.resolvedModel, span.providerId,
            span.inputTokens, span.outputTokens, span.cachedTokens, span.startedAt, attrCost);
        span.costUsd = price.costUsd();
        span.costSource = price.source();
        span.pricingModelId = price.modelId();
        span.inputPricePer1MTokens = price.inputPricePer1MTokens();
        span.outputPricePer1MTokens = price.outputPricePer1MTokens();
    }
}
