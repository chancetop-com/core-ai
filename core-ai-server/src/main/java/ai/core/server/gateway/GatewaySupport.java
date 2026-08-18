package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.http.HTTPRequest;
import core.framework.web.Request;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class GatewaySupport {
    static final long DEFAULT_TIMEOUT_SECONDS = 120;

    // Per-conversation session headers sent by LLM CLI terminals (Claude Code, Codex, ...).
    // cc-switch forwards them upstream; extend this table when supporting new terminals.
    private static final String[] CLIENT_SESSION_HEADERS = {
        "X-Claude-Code-Session-Id",
        "session_id",
        "x-session-id",
        "session-id"
    };
    // The session id lets the ingest layer merge one client conversation into a single trace.
    static String clientSessionId(Request request) {
        for (var header : CLIENT_SESSION_HEADERS) {
            var sessionId = request.header(header).orElse(null);
            if (hasText(sessionId)) return sessionId;
        }
        return null;
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String trimToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String stripTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    static long valueOrDefault(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static void applyAuth(GatewayProviderConfig provider, HTTPRequest request, String apiKey) {
        if (isBlank(apiKey)) return;
        if ("azure".equals(provider.type)) {
            request.headers.put("api-key", apiKey);
        } else if ("gemini".equals(provider.type)) {
            request.headers.put("x-goog-api-key", apiKey);
        } else {
            request.headers.put("Authorization", "Bearer " + apiKey);
        }
    }

    // OpenAI chat/completions uses prompt_tokens/completion_tokens, responses uses input_tokens/output_tokens
    static GatewayUsage parseUsage(Map<String, Object> body) {
        if (body == null || !(body.get("usage") instanceof Map<?, ?> usage)) return null;
        long input = number(usage.get("prompt_tokens"), usage.get("input_tokens"));
        long output = number(usage.get("completion_tokens"), usage.get("output_tokens"));
        if (input == 0 && output == 0) return null;
        Object cached = usage.get("cached_tokens");
        if (cached == null) cached = detailValue(usage, "prompt_tokens_details");
        if (cached == null) cached = detailValue(usage, "input_tokens_details");
        return new GatewayUsage(input, output, number(cached, null));
    }

    private static long number(Object primary, Object fallback) {
        if (primary instanceof Number value) return value.longValue();
        if (fallback instanceof Number value) return value.longValue();
        return 0;
    }

    private static Object detailValue(Map<?, ?> usage, String detailsKey) {
        return usage.get(detailsKey) instanceof Map<?, ?> details ? details.get("cached_tokens") : null;
    }

    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private GatewaySupport() {
    }
}
