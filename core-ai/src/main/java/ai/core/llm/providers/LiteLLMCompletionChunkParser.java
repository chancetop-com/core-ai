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
        var dataMap = JsonUtil.toMap(LiteLLMProvider.repairInvalidJsonEscapes(data));
        normalizeReasoningAlias(dataMap);
        return JsonUtil.fromJson(CompletionResponse.class, dataMap);
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
