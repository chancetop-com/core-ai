package ai.core.agent;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author stephen
 */
final class MessageNormalizer {

    static void normalize(Node<?> node) {
        var messages = node.getMessages();
        Set<String> toolResultIds = new HashSet<>();
        Set<String> orphanToolUses = new LinkedHashSet<>();

        for (var msg : messages) {
            if (msg.role == RoleType.TOOL && msg.toolCallId != null) {
                toolResultIds.add(msg.toolCallId);
            }
        }
        for (var msg : messages) {
            if (msg.role == RoleType.ASSISTANT && msg.toolCalls != null) {
                for (var tc : msg.toolCalls) {
                    if (!toolResultIds.contains(tc.id)) {
                        orphanToolUses.add(tc.id);
                    }
                }
            }
        }

        for (var orphanId : orphanToolUses) {
            node.addMessage(Message.of(RoleType.TOOL,
                    "Error: Tool execution was cancelled or interrupted.",
                    "system", orphanId, null));
        }
    }

    private MessageNormalizer() {
    }
}
