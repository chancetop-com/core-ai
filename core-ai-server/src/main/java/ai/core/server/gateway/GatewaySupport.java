package ai.core.server.gateway;

import ai.core.http.GatewayHeaderCodec;
import ai.core.server.domain.GatewayProviderConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.http.HTTPRequest;
import core.framework.web.Request;
import core.framework.web.exception.BadRequestException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GatewaySupport {
    static final long DEFAULT_TIMEOUT_SECONDS = 120;
    static final int MAX_SYNTHESIZED_TOOL_CALLS = 20;

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

    // Agent name sent by framework clients (x-agent-name) so the gateway can synthesize an agent
    // layer above the LLM span, mirroring the agent/llm/tool hierarchy of server-side chat traces.
    // Non-ASCII names travel as an RFC 2047 encoded-word (see GatewayHeaderCodec); plain ASCII
    // values from external clients pass through unchanged.
    static String agentName(Request request) {
        var value = trimToNull(request.header("x-agent-name").orElse(null));
        if (value == null) return null;
        return GatewayHeaderCodec.decode(value);
    }

    // Chat requests replay the previous tool executions as messages: the assistant message holds
    // tool_calls (id + function.name + function.arguments) and the tool message holds the result.
    // Pairing them lets the gateway synthesize tool spans without any extra client reporting.
    static List<GatewayToolCall> parseToolCalls(Map<String, Object> body, int maxCalls) {
        var messages = body.get("messages");
        if (!(messages instanceof List<?> messageList) || messageList.isEmpty()) return List.of();
        var calls = new LinkedHashMap<String, String[]>();
        var results = new ArrayList<GatewayToolCall>();
        for (var item : messageList) {
            if (!(item instanceof Map<?, ?> message)) continue;
            collectToolCalls(message.get("tool_calls"), calls);
            if (!"tool".equals(string(message.get("role")))) continue;
            var id = string(message.get("tool_call_id"));
            if (!hasText(id)) continue;
            var definition = calls.remove(id);
            if (definition == null) continue;
            results.add(new GatewayToolCall(id, definition[0], definition[1], contentText(message.get("content"))));
            if (results.size() >= maxCalls) break;
        }
        return results;
    }

    // message content arrives either as a plain string or as OpenAI content parts
    // ([{"type":"text","text":"..."}]); normalize both to the text form
    static String contentText(Object content) {
        if (content instanceof String text) return text;
        if (content instanceof List<?> parts) {
            var builder = new StringBuilder();
            for (var part : parts) {
                if (!(part instanceof Map<?, ?> map) || !(map.get("text") instanceof String text) || !hasText(text)) continue;
                if (builder.length() > 0) builder.append('\n');
                builder.append(text);
            }
            return builder.length() > 0 ? builder.toString() : null;
        }
        return null;
    }

    private static void collectToolCalls(Object value, Map<String, String[]> calls) {
        if (!(value instanceof List<?> toolCallList)) return;
        for (var call : toolCallList) {
            if (!(call instanceof Map<?, ?> callMap)) continue;
            var function = callMap.get("function");
            if (!(function instanceof Map<?, ?> functionMap)) continue;
            var id = string(callMap.get("id"));
            var name = string(functionMap.get("name"));
            if (hasText(id) && hasText(name)) {
                calls.put(id, new String[]{name, string(functionMap.get("arguments"))});
            }
        }
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

    static String string(Object value) {
        return value instanceof String string ? string : null;
    }

    static long valueOrDefault(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static boolean isVertexGeminiBaseUrl(String baseUrl) {
        return baseUrl != null && baseUrl.contains("aiplatform.googleapis.com");
    }

    // Gemini models are served through Google's OpenAI-compatible endpoints: Vertex hosts them
    // under /projects/{project}/locations/{location}/endpoints/openapi, the Developer API under
    // /v1beta/openai. The provider baseUrl alone points at the native REST root, which has no
    // /chat/completions path.
    static String geminiOpenAiCompatibleUrl(GatewayProviderConfig provider) {
        var baseUrl = stripTrailingSlash(provider.baseUrl);
        if (isVertexGeminiBaseUrl(baseUrl)) {
            if (isBlank(provider.vertexProjectId) || isBlank(provider.vertexLocation)) {
                throw new BadRequestException("Vertex gemini chat requires vertexProjectId and vertexLocation on provider: " + provider.name);
            }
            return baseUrl + "/projects/" + urlEncode(provider.vertexProjectId)
                    + "/locations/" + urlEncode(provider.vertexLocation) + "/endpoints/openapi";
        }
        return baseUrl.endsWith("/openai") ? baseUrl : baseUrl + "/openai";
    }

    static void validateProviderEndpointCompatibility(GatewayProviderConfig provider, List<String> endpointTypes) {
        if (endpointTypes == null || endpointTypes.isEmpty() || !"gemini".equals(provider.type)) return;
        if (endpointTypes.contains(GatewayModelService.ENDPOINT_RESPONSES)) {
            throw new BadRequestException("gemini provider does not support the responses endpoint, provider=" + provider.name);
        }
        if (!endpointTypes.contains(GatewayModelService.ENDPOINT_CHAT_COMPLETIONS)) return;
        if (isVertexGeminiBaseUrl(provider.baseUrl)) {
            if (isBlank(provider.vertexProjectId) || isBlank(provider.vertexLocation)) {
                throw new BadRequestException("Vertex gemini chat models require vertexProjectId and vertexLocation on provider: " + provider.name);
            }
            if (isBlank(provider.googleCredentialsEncrypted)) {
                throw new BadRequestException("Vertex gemini chat models require googleCredentialsJson (service account) on provider: " + provider.name);
            }
        } else if (isBlank(provider.apiKey) && isBlank(provider.apiKeyEncrypted)) {
            throw new BadRequestException("gemini chat models require an apiKey on provider: " + provider.name);
        }
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

    // A tool execution inferred from request messages: tool_calls supplies the definition and the
    // matching role=tool message supplies the result.
    record GatewayToolCall(String toolCallId, String name, String arguments, String content) {
    }
}
