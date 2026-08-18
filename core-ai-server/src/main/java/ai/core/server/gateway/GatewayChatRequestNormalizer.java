package ai.core.server.gateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Sanitizes chat.completions request bodies before forwarding: drops parameters the upstream
 * model rejects and repairs message sequences that would fail upstream validation.
 */
final class GatewayChatRequestNormalizer {
    // stub body for a tool call whose result never arrived (e.g. an interrupted Codex session converted to chat);
    // upstreams require a tool message for every assistant tool_call id, so fill in the missing ones
    private static final String INTERRUPTED_TOOL_RESULT = "[Tool call was interrupted and produced no result]";

    static void normalize(Map<String, Object> outgoingBody, Boolean supportsReasoningEffort) {
        // Azure gpt-5.x chat deployments reject the reasoning_effort parameter; drop the field when the model declares no support
        if (Boolean.FALSE.equals(supportsReasoningEffort)) outgoingBody.remove("reasoning_effort");
        completeMissingToolResults(outgoingBody);
    }

    // upstreams reject an assistant tool_calls message without a tool message answering every call id
    // (e.g. an interrupted Codex session converted to chat); append a stub result for each missing id
    private static void completeMissingToolResults(Map<String, Object> outgoingBody) {
        var messages = outgoingBody.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) return;
        var answeredIds = new HashSet<String>();
        for (var message : list) {
            if (message instanceof Map<?, ?> map && "tool".equals(map.get("role"))) {
                var id = map.get("tool_call_id");
                if (id instanceof String toolCallId) answeredIds.add(toolCallId);
            }
        }
        var completed = new ArrayList<Object>(list.size() + 2);
        for (var message : list) {
            completed.add(message);
            if (!(message instanceof Map<?, ?> map) || !"assistant".equals(map.get("role"))) continue;
            var toolCalls = map.get("tool_calls");
            if (!(toolCalls instanceof List<?> calls)) continue;
            for (var call : calls) {
                if (!(call instanceof Map<?, ?> callMap)) continue;
                var id = callMap.get("id");
                if (id instanceof String toolCallId && !answeredIds.contains(toolCallId)) {
                    completed.add(Map.of("role", "tool", "tool_call_id", toolCallId, "content", INTERRUPTED_TOOL_RESULT));
                }
            }
        }
        if (completed.size() != list.size()) outgoingBody.put("messages", completed);
    }

    private GatewayChatRequestNormalizer() {
    }
}
