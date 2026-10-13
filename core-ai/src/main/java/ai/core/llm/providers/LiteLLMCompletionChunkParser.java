package ai.core.llm.providers;

import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.CompletionResponse;
import ai.core.utils.JsonUtil;

import java.util.List;
import java.util.Map;

/**
 * Parses chat/completions SSE chunks. Providers expose reasoning text either as
 * {@code reasoning_content} (OpenAI-style) or {@code reasoning} (e.g. Gemini-compatible
 * endpoints); the latter is renamed so both shapes land in {@link AssistantMessage#reasoningContent}.
 *
 * @author stephen
 */
final class LiteLLMCompletionChunkParser {
    static CompletionResponse parse(String data) {
        var dataMap = JsonUtil.toMap(repairInvalidJsonEscapes(data));
        var error = errorMessage(dataMap);
        if (error != null) throw new LLMStreamException(error);
        normalizeReasoningAlias(dataMap);
        return JsonUtil.fromJson(CompletionResponse.class, dataMap);
    }

    // Some upstreams (and the server-side CLI proxy) report a failure as an in-stream data event
    // rather than an HTTP error: OpenAI style {"error":{...}} or core-ng SSE style {"type":"error",...}.
    private static String errorMessage(Map<String, Object> dataMap) {
        if (dataMap.get("error") instanceof Map<?, ?> error) return errorText(error);
        if (dataMap.get("error") instanceof String text && !text.isBlank()) return text;
        if ("error".equals(dataMap.get("type"))) return errorText(dataMap);
        return null;
    }

    private static String errorText(Map<?, ?> error) {
        var message = error.get("message");
        return message instanceof String text && !text.isBlank() ? text : JsonUtil.toJson(error);
    }

    // Some providers emit invalid escape sequences inside string values; repair them before parsing.
    static String repairInvalidJsonEscapes(String json) {
        var repaired = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);
            if (!inString) {
                if (current == '"') inString = true;
                repaired.append(current);
                continue;
            }
            if (escaped) {
                if (current != '"' && current != '\\' && current != '/' && current != 'b' && current != 'f'
                        && current != 'n' && current != 'r' && current != 't' && current != 'u') {
                    repaired.append('\\');
                }
                repaired.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                repaired.append(current);
                escaped = true;
            } else {
                if (current == '"') inString = false;
                repaired.append(current);
            }
        }
        if (escaped) repaired.append('\\');
        return repaired.toString();
    }

    @SuppressWarnings("unchecked")
    private static void normalizeReasoningAlias(Map<String, Object> dataMap) {
        if (!(dataMap.get("choices") instanceof List<?> choices)) return;
        for (var rawChoice : choices) {
            if (!(rawChoice instanceof Map<?, ?> rawChoiceMap)) continue;
            var choiceMap = (Map<String, Object>) rawChoiceMap;
            for (var containerKey : List.of("delta", "message")) {
                if (!(choiceMap.get(containerKey) instanceof Map<?, ?> rawContainer)) continue;
                var container = (Map<String, Object>) rawContainer;
                if (container.get("reasoning") instanceof String && !container.containsKey("reasoning_content")) {
                    container.put("reasoning_content", container.remove("reasoning"));
                }
            }
        }
    }

    private LiteLLMCompletionChunkParser() {
    }
}
