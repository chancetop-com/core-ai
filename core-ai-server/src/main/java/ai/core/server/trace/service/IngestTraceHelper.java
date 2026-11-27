package ai.core.server.trace.service;

import ai.core.server.trace.TraceAttribute;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.server.trace.web.ingest.IngestRequest;
import ai.core.server.trace.web.ingest.IngestSpanRequest;
import ai.core.server.trace.web.ingest.IngestStartRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Pure trace/span mapping and construction helpers shared by {@link IngestService}.
 *
 * @author stephen
 */
final class IngestTraceHelper {
    static final String GEN_AI_AGENT_NAME = "gen_ai.agent.name";
    static final String GEN_AI_AGENT_ID = "gen_ai.agent.id";
    static final String DEFAULT_START_TRACE_NAME = "cli turn";

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestTraceHelper.class);
    private static final String CORE_AI_CANCELLED = "core_ai.cancelled";
    private static final long MAX_START_PAST_MS = Duration.ofDays(30).toMillis();
    private static final long MAX_START_FUTURE_MS = Duration.ofMinutes(5).toMillis();

    static String attr(Map<String, String> attributes, String key) {
        return attributes != null ? nonBlank(attributes.get(key)) : null;
    }

    static String nonBlank(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    static boolean isOwnerMismatch(Trace existing, String authUserId) {
        var ownerUserId = nonBlank(existing.userId);
        return ownerUserId != null && !ownerUserId.equals(authUserId);
    }

    static String resolveStartName(String name) {
        var trimmed = name != null ? name.trim() : null;
        return trimmed != null && !trimmed.isEmpty() ? trimmed : DEFAULT_START_TRACE_NAME;
    }

    static Trace buildStartTrace(IngestStartRequest request, String authUserId, String source, ZonedDateTime now) {
        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = request.traceId;
        trace.name = resolveStartName(request.name);
        trace.type = "agent";
        trace.status = TraceStatus.RUNNING;
        trace.source = source;
        trace.userId = authUserId;
        trace.startedAt = resolveStartTime(request.traceId, request.startedAtEpochMs, now);
        trace.durationMs = 0L;
        trace.input = request.input;
        trace.metadata = metadata(request.serviceName, request.serviceVersion, request.environment);
        trace.createdAt = now;
        trace.updatedAt = now;
        TraceServiceHelper.initializeTotals(trace);
        return trace;
    }

    static Trace buildTrace(String traceId, IngestSpanRequest representative, IngestRequest request,
                            String authUserId, String source, boolean provisional) {
        var now = ZonedDateTime.now();
        var trace = new Trace();
        trace.id = UUID.randomUUID().toString();
        trace.traceId = traceId;
        trace.agentName = attr(representative.attributes, GEN_AI_AGENT_NAME);
        trace.agentId = attr(representative.attributes, GEN_AI_AGENT_ID);
        trace.model = representative.model;
        trace.resolvedModel = resolveResolvedModel(representative);
        trace.providerId = attr(representative.attributes, TraceAttribute.GATEWAY_PROVIDER_ID);
        trace.sessionId = representative.attributes != null ? representative.attributes.get("session.id") : null;
        trace.userId = IngestService.resolveUserId(authUserId, representative.attributes);
        trace.source = source;
        applyTracePayloadFields(trace, representative, provisional);
        trace.metadata = metadata(request.serviceName, request.serviceVersion, request.environment);
        trace.startedAt = toZonedDateTime(representative.startedAtEpochMs);
        if (provisional && trace.startedAt == null) trace.startedAt = now;
        trace.createdAt = now;
        trace.updatedAt = now;
        TraceServiceHelper.initializeTotals(trace);
        return trace;
    }

    private static void applyTracePayloadFields(Trace trace, IngestSpanRequest representative, boolean provisional) {
        if (provisional) {
            trace.type = "agent";
            trace.status = TraceStatus.RUNNING;
            trace.durationMs = 0L;
            return;
        }
        trace.name = IngestService.friendlyTraceName(representative.name, trace.agentName);
        trace.type = mapTraceType(representative.type);
        trace.status = mapTraceStatus(representative.status, representative.attributes);
        if (trace.status == TraceStatus.ERROR && representative.output != null) {
            trace.errorMessage = representative.output;
        }
        trace.input = representative.input;
        trace.output = representative.output;
        trace.durationMs = representative.durationMs;
        trace.completedAt = toZonedDateTime(representative.completedAtEpochMs);
    }

    static Map<String, String> metadata(String serviceName, String serviceVersion, String environment) {
        return Map.of("service", serviceName != null ? serviceName : "unknown",
            "version", serviceVersion != null ? serviceVersion : "unknown",
            "environment", environment != null ? environment : "unknown");
    }

    static ZonedDateTime resolveStartTime(String traceId, Long startedAtEpochMs, ZonedDateTime now) {
        if (startedAtEpochMs == null) return now;
        long nowMs = now.toInstant().toEpochMilli();
        if (startedAtEpochMs < nowMs - MAX_START_PAST_MS || startedAtEpochMs > nowMs + MAX_START_FUTURE_MS) {
            LOGGER.warn("trace start timestamp out of range, clamping to now, traceId={}, startedAtEpochMs={}",
                traceId, startedAtEpochMs);
            return now;
        }
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(startedAtEpochMs), ZoneId.systemDefault());
    }

    static String resolveResolvedModel(IngestSpanRequest spanReq) {
        var resolved = nonBlank(spanReq.resolvedModel);
        if (resolved != null) return resolved;
        return attr(spanReq.attributes, TraceAttribute.GEN_AI_RESOLVED_MODEL);
    }

    static Long resolveCachedTokens(IngestSpanRequest spanReq) {
        if (spanReq.cachedTokens != null) return spanReq.cachedTokens;
        return parseLongAttr(spanReq.attributes,
            "gen_ai.usage.cached_tokens",
            "gen_ai.usage.prompt_tokens_details.cached_tokens",
            "gen_ai.usage.input_tokens_details.cached_tokens",
            "usage.prompt_tokens_details.cached_tokens",
            "prompt_tokens_details.cached_tokens");
    }

    static Long resolveTtftMs(IngestSpanRequest spanReq) {
        if (spanReq.ttftMs != null) return spanReq.ttftMs;
        return parseLongAttr(spanReq.attributes, TraceServiceHelper.GEN_AI_TTFT_MS, TraceServiceHelper.LEGACY_TTFT_MS);
    }

    static Long parseLongAttr(Map<String, String> attributes, String... keys) {
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

    static Double parseDoubleAttr(Map<String, String> attributes, String... keys) {
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

    static SpanStatus mapSpanStatus(String status, Map<String, String> attributes) {
        if (isCancelled(status, attributes)) return SpanStatus.CANCELLED;
        if ("ERROR".equals(status)) return SpanStatus.ERROR;
        if ("WARNING".equals(status)) return SpanStatus.WARNING;
        return SpanStatus.OK;
    }

    static TraceStatus mapTraceStatus(String status, Map<String, String> attributes) {
        if (isCancelled(status, attributes)) return TraceStatus.CANCELLED;
        if ("ERROR".equals(status)) return TraceStatus.ERROR;
        if ("OK".equals(status)) return TraceStatus.COMPLETED;
        return TraceStatus.RUNNING;
    }

    static String mapTraceType(String rootSpanType) {
        if ("LLM".equals(rootSpanType)) return "llm_call";
        return "agent";
    }

    static SpanType mapSpanType(String type) {
        if (type == null) return SpanType.AGENT;
        return switch (type) {
            case "LLM" -> SpanType.LLM;
            case "TOOL" -> SpanType.TOOL;
            case "FLOW" -> SpanType.FLOW;
            case "GROUP" -> SpanType.GROUP;
            default -> SpanType.AGENT;
        };
    }

    static ZonedDateTime toZonedDateTime(long epochMs) {
        if (epochMs <= 0) return null;
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private static boolean isCancelled(String status, Map<String, String> attributes) {
        return "CANCELLED".equals(status)
            || attributes != null && "true".equalsIgnoreCase(attributes.get(CORE_AI_CANCELLED));
    }

    private IngestTraceHelper() {
    }
}
