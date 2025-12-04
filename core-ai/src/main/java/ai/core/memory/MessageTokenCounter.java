package ai.core.memory;

import ai.core.document.Tokenizer;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.List;

/**
 * Utility for counting tokens in messages.
 *
 * @author xander
 */
public final class MessageTokenCounter {

    private MessageTokenCounter() {
    }

    /**
     * Count tokens in a single message.
     */
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

    /**
     * Count tokens in multiple messages.
     */
    public static int count(List<Message> messages) {
        return countFrom(messages, 0, false);
    }

    /**
     * Count tokens from a specific index.
     */
    public static int countFrom(List<Message> messages, int fromIndex) {
        return countFrom(messages, fromIndex, false);
    }

    /**
     * Count tokens from a specific index, optionally excluding system messages.
     *
     * @param messages      the messages to count
     * @param fromIndex     starting index
     * @param excludeSystem whether to skip SYSTEM role messages
     * @return total token count
     */
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
}
