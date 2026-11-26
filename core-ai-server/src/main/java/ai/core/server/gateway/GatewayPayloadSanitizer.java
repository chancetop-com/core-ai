package ai.core.server.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strips historical reasoning_content fields from chat completion payloads before forwarding upstream.
 *
 * @author stephen
 */
final class GatewayPayloadSanitizer {
    @SuppressWarnings("unchecked")
    static void stripHistoricalReasoningContent(Map<String, Object> body) {
        if (!(body.get("messages") instanceof List<?> rawMessages) || rawMessages.isEmpty()) {
            return;
        }
        int lastUserIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            if (rawMessages.get(i) instanceof Map<?, ?> msg && "user".equals(msg.get("role"))) {
                lastUserIndex = i;
                break;
            }
        }
        boolean modified = false;
        var updated = new ArrayList<Object>(rawMessages.size());
        for (int i = 0; i < rawMessages.size(); i++) {
            var item = rawMessages.get(i);
            if (item instanceof Map<?, ?> rawMap) {
                var rc = rawMap.get("reasoning_content");
                boolean present = rc != null || rawMap.containsKey("reasoning_content");
                boolean strip = present && (i < lastUserIndex || rc == null || rc instanceof String s && s.isEmpty());
                if (strip) {
                    var copy = new LinkedHashMap<String, Object>((Map<String, Object>) rawMap);
                    copy.remove("reasoning_content");
                    updated.add(copy);
                    modified = true;
                    continue;
                }
            }
            updated.add(item);
        }
        if (modified) {
            body.put("messages", updated);
        }
    }

    private GatewayPayloadSanitizer() {
    }
}
