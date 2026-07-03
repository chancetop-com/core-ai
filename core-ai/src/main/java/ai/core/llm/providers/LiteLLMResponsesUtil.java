package ai.core.llm.providers;

import ai.core.llm.domain.FinishReason;

import java.util.LinkedHashMap;
import java.util.Map;

final class LiteLLMResponsesUtil {
    static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    static Map<String, Object> mapOf(String key, Object value) {
        var map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    static Map<String, Object> asStringObjectMap(Map<?, ?> raw) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }

    static Integer intValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    static int intValue(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    static String finishReasonValue(FinishReason finishReason) {
        return switch (finishReason) {
            case STOP -> "stop";
            case TOOL_CALLS -> "tool_calls";
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
        };
    }

    private LiteLLMResponsesUtil() {
    }
}
