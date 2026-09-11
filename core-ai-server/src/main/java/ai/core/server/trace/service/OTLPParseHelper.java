package ai.core.server.trace.service;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.TraceStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private static helpers for OTLP span attribute parsing.
 *
 * @author stephen
 */
class OTLPParseHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(OTLPParseHelper.class);

    static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static Map<String, String> extractAttributes(List<KeyValue> kvList) {
        var map = new LinkedHashMap<String, String>();
        for (KeyValue kv : kvList) {
            map.put(kv.getKey(), anyValueToString(kv.getValue()));
        }
        return map;
    }

    static String anyValueToString(AnyValue value) {
        if (value.hasStringValue()) return value.getStringValue();
        if (value.hasIntValue()) return String.valueOf(value.getIntValue());
        if (value.hasDoubleValue()) return String.valueOf(value.getDoubleValue());
        if (value.hasBoolValue()) return String.valueOf(value.getBoolValue());
        return value.toString();
    }

    static ZonedDateTime toZonedDateTime(long epochMs) {
        if (epochMs <= 0) return null;
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    static Long parseLongAttr(Map<String, String> attrs, String... keys) {
        for (var key : keys) {
            var value = attrs.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                LOGGER.debug("invalid long trace attribute {}={}", key, value);
            }
        }
        return null;
    }

    static Double parseDoubleAttr(Map<String, String> attrs, String... keys) {
        for (var key : keys) {
            var value = attrs.get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Double.valueOf(value);
            } catch (NumberFormatException ignored) {
                LOGGER.debug("invalid double trace attribute {}={}", key, value);
            }
        }
        return null;
    }

    static long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // Gateway requests carrying a client session id (Claude Code's X-Claude-Code-Session-Id) share one
    // derived trace id, so one client conversation merges into a single trace instead of one trace per request.
    static String resolveTraceId(io.opentelemetry.proto.trace.v1.Span protoSpan, Map<String, String> attrs) {
        var protoTraceId = bytesToHex(protoSpan.getTraceId().toByteArray());
        if (!"gateway".equals(attrs.get("client.type"))) return protoTraceId;
        var sessionId = attrs.get("session.id");
        if (sessionId == null || sessionId.isBlank()) return protoTraceId;
        var userId = attrs.get("user.id");
        var key = (userId == null || userId.isBlank() ? "" : userId) + '\u0000' + sessionId;
        return sha256Hex(key).substring(0, 32);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String resolveInput(Map<String, String> attrs) {
        var input = attrs.get("gen_ai.prompt");
        if (input != null) return input;
        return attrs.get("langfuse.observation.input");
    }

    static String resolveOutput(Map<String, String> attrs) {
        var output = attrs.get("gen_ai.completion");
        if (output != null) return output;
        return attrs.get("langfuse.observation.output");
    }

    static SpanStatus mapSpanStatus(Status.StatusCode code, Map<String, String> attrs) {
        if (isCancelled(attrs)) return SpanStatus.CANCELLED;
        if (code == Status.StatusCode.STATUS_CODE_ERROR) return SpanStatus.ERROR;
        return SpanStatus.OK;
    }

    static TraceStatus mapTraceStatus(Status.StatusCode code, Map<String, String> attrs) {
        if (isCancelled(attrs)) return TraceStatus.CANCELLED;
        if (code == Status.StatusCode.STATUS_CODE_ERROR) return TraceStatus.ERROR;
        return TraceStatus.COMPLETED;
    }

    private static boolean isCancelled(Map<String, String> attrs) {
        return "true".equalsIgnoreCase(attrs.get("core_ai.cancelled"));
    }
}
