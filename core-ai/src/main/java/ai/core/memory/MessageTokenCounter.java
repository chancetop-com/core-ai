package ai.core.memory;

import ai.core.document.Tokenizer;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.List;

/**
 * @author xander
 */
public final class MessageTokenCounter {

    public static int count(Message message) {
        int tokens = 0;
        if (message.content != null) {
            tokens += Tokenizer.tokenCount(message.content);
        }
        if (message.toolCalls != null) {
            for (var call : message.toolCalls) {
                if (call.function != null && call.function.arguments != null) {
                    tokens += Tokenizer.tokenCount(call.function.arguments);
                }
            }
        }
        return tokens;
    }

    public static int count(List<Message> messages) {
        return countFrom(messages, 0, false);
    }

    public static int countFrom(List<Message> messages, int fromIndex) {
        return countFrom(messages, fromIndex, false);
    }

    public static int countFrom(List<Message> messages, int fromIndex, boolean excludeSystem) {
        int total = 0;
        for (int i = fromIndex; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (excludeSystem && msg.role == RoleType.SYSTEM) {
                continue;
            }
            total += count(msg);
        }
        return total;
    }

    private MessageTokenCounter() {
    }
}
