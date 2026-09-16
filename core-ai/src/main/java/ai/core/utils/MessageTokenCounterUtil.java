package ai.core.utils;

import ai.core.document.Tokenizer;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.List;

/**
 * @author xander
 */
public final class MessageTokenCounterUtil {
    /**
     * Rough per-part cost for attachments whose payload the tokenizer cannot see. A 1024x1024 image is
     * about 1000 tokens on the common vision models; files and videos are usually more. The counter only
     * drives the compression trigger, so the estimate has to be the right order of magnitude, not exact.
     */
    private static final int ATTACHMENT_TOKENS = 1000;

    public static int count(Message message) {
        int tokens = 0;
        if (message.content != null) {
            for (var part : message.content) {
                tokens += countPart(part);
            }
        }
        if (message.reasoningContent != null) {
            tokens += Tokenizer.tokenCount(message.reasoningContent);
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

    private static int countPart(Content part) {
        if (part == null || part.type == null) {
            return 0;
        }
        return switch (part.type) {
            case TEXT -> part.text != null ? Tokenizer.tokenCount(part.text) : 0;
            case IMAGE_URL, FILE, VIDEO -> ATTACHMENT_TOKENS;
        };
    }

    private MessageTokenCounterUtil() {
    }
}
