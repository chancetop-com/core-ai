package ai.core.agent;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.utils.MessageTokenCounterUtil;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * @author stephen
 */
final class AgentFootprintCalculator {
    private static final long MESSAGE_OVERHEAD_BYTES = 256;
    private static final long SESSION_OVERHEAD_BYTES = 4096;

    static AgentFootprint calculate(List<Message> messages, List<Message> history) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Message, Boolean>());
        var totals = new Totals();
        if (messages != null) {
            for (var message : messages) {
                if (seen.add(message)) totals.add(message);
            }
        }
        if (history != null) {
            for (var message : history) {
                if (seen.add(message)) totals.add(message);
            }
        }
        long estimatedBytes = totals.contentBytes + seen.size() * MESSAGE_OVERHEAD_BYTES + SESSION_OVERHEAD_BYTES;
        long contextTokens = messages == null ? 0 : MessageTokenCounterUtil.count(messages);
        return new AgentFootprint(turnCount(history, messages), messages == null ? 0 : messages.size(),
                history == null ? 0 : history.size(), contextTokens, totals.textBytes, totals.inlineImageBytes,
                totals.fileBytes, totals.videoBytes, totals.reasoningBytes, totals.toolCallBytes,
                totals.toolResultBytes, estimatedBytes);
    }

    private static int turnCount(List<Message> history, List<Message> messages) {
        var source = history != null && !history.isEmpty() ? history : messages;
        if (source == null) return 0;
        int turns = 0;
        for (var message : source) {
            if (message != null && message.role == RoleType.USER && !AgentInterruptionHandler.isInterruptionMarker(message)) {
                turns++;
            }
        }
        return turns;
    }

    private static long length(String value) {
        return value == null ? 0 : value.length();
    }

    private static final class Totals {
        private long contentBytes;
        private long textBytes;
        private long inlineImageBytes;
        private long fileBytes;
        private long videoBytes;
        private long reasoningBytes;
        private long toolCallBytes;
        private long toolResultBytes;

        private void add(Message message) {
            if (message == null) return;
            if (message.content != null) {
                for (var part : message.content) {
                    add(part);
                }
            }
            if (message.reasoningContent != null) {
                reasoningBytes += message.reasoningContent.length();
                contentBytes += message.reasoningContent.length();
            }
            if (message.toolCalls != null) {
                for (var call : message.toolCalls) {
                    add(call);
                }
            }
            if (message.role == RoleType.TOOL) {
                var text = message.getJoinedTextContent();
                if (text != null) toolResultBytes += text.length();
            }
        }

        private void add(Content part) {
            if (part == null || part.type == null) return;
            switch (part.type) {
                case TEXT -> {
                    if (part.text != null) {
                        textBytes += part.text.length();
                        contentBytes += part.text.length();
                    }
                }
                case IMAGE_URL -> {
                    if (part.imageUrl != null && part.imageUrl.url != null) {
                        long length = part.imageUrl.url.length();
                        contentBytes += length;
                        if (part.imageUrl.url.startsWith("data:")) inlineImageBytes += length;
                    }
                }
                case FILE -> {
                    if (part.file != null) {
                        long length = length(part.file.fileData) + length(part.file.fileId) + length(part.file.filename);
                        fileBytes += length;
                        contentBytes += length;
                    }
                }
                case VIDEO -> {
                    if (part.video != null) {
                        long length = length(part.video.uri) + length(part.video.mediaType) + length(part.video.filename);
                        videoBytes += length;
                        contentBytes += length;
                    }
                }
                default -> throw new IllegalStateException("unsupported content type: " + part.type);
            }
        }

        private void add(FunctionCall call) {
            if (call == null) return;
            long length = length(call.id) + length(call.type);
            if (call.function != null) {
                length += length(call.function.name) + length(call.function.arguments);
            }
            toolCallBytes += length;
            contentBytes += length;
        }
    }
}
