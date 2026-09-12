package ai.core.llm;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import core.framework.util.Strings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Request-time size guard for stateless chat completion calls: the whole history is resent on every
 * turn, so the inline base64 images accumulated by a long agent session eventually push the request
 * body past the upstream limit (DeepSeek answers HTTP 413 Request Entity Too Large).
 * The newest inline image is always kept, older ones survive newest-first while the count and byte
 * budgets hold; whatever does not fit becomes a text placeholder the agent can restore by re-reading
 * or re-attaching the source. Placeholder text is derived from the message only, never from a
 * counter, so the prompt cache prefix stays valid across turns.
 * Inline files (base64 PDFs) are out of scope, they are only ever attached by the user.
 *
 * @author stephen
 */
public final class InlineImagePruner {
    public static final int DEFAULT_MAX_IMAGES = 8;
    public static final long DEFAULT_MAX_BYTES = 12L * 1024 * 1024;

    public static PruneResult prune(List<Message> messages) {
        return prune(messages, DEFAULT_MAX_IMAGES, DEFAULT_MAX_BYTES);
    }

    /**
     * @param maxImages inline images to keep; 0 drops every inline image, which is how the 413
     *                  retry rescues a request that is too large even after budget pruning,
     *                  negative means no count limit
     * @param maxBytes  byte budget for the kept inline images, decoded rather than base64 length,
     *                  non-positive means no byte limit
     */
    public static PruneResult prune(List<Message> messages, int maxImages, long maxBytes) {
        if (messages == null || messages.isEmpty()) return new PruneResult(messages, 0, 0);
        var slots = collectInlineImages(messages);
        if (slots.isEmpty()) return new PruneResult(messages, 0, 0);
        var kept = selectKept(slots, maxImages, maxBytes);
        if (allKept(kept)) return new PruneResult(messages, 0, 0);
        return rewrite(messages, slots, kept);
    }

    private static List<ImageSlot> collectInlineImages(List<Message> messages) {
        var slots = new ArrayList<ImageSlot>();
        for (var messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            var content = messages.get(messageIndex).content;
            if (content == null) continue;
            for (var partIndex = 0; partIndex < content.size(); partIndex++) {
                var part = content.get(partIndex);
                if (!isInlineImage(part)) continue;
                slots.add(new ImageSlot(messageIndex, partIndex, decodedBytes(part.imageUrl.url)));
            }
        }
        return slots;
    }

    private static boolean[] selectKept(List<ImageSlot> slots, int maxImages, long maxBytes) {
        var kept = new boolean[slots.size()];
        if (maxImages == 0) return kept;
        var maxCount = maxImages < 0 ? Integer.MAX_VALUE : maxImages;
        long bytes = 0;
        var count = 0;
        for (var i = slots.size() - 1; i >= 0; i--) {
            var slot = slots.get(i);
            // the newest image is resent even when it alone exceeds the budget: hiding what the agent
            // just looked at makes it read the same file again, and a request that is still too large
            // is recovered by the 413 retry instead
            var newest = i == slots.size() - 1;
            if (!newest && (count >= maxCount || maxBytes > 0 && bytes + slot.bytes() > maxBytes)) continue;
            kept[i] = true;
            count++;
            bytes += slot.bytes();
        }
        return kept;
    }

    private static PruneResult rewrite(List<Message> messages, List<ImageSlot> slots, boolean[] kept) {
        var replaced = new HashMap<Integer, List<Content>>();
        var prunedBytes = 0L;
        var prunedCount = 0;
        for (var i = 0; i < slots.size(); i++) {
            if (kept[i]) continue;
            var slot = slots.get(i);
            var message = messages.get(slot.messageIndex());
            var content = replaced.computeIfAbsent(slot.messageIndex(), index -> new ArrayList<>(message.content));
            content.set(slot.partIndex(), Content.of(placeholder(message.name)));
            prunedCount++;
            prunedBytes += slot.bytes();
        }
        var result = new ArrayList<Message>(messages.size());
        for (var i = 0; i < messages.size(); i++) {
            var content = replaced.get(i);
            result.add(content == null ? messages.get(i) : copyWithContent(messages.get(i), List.copyOf(content)));
        }
        return new PruneResult(List.copyOf(result), prunedCount, prunedBytes);
    }

    private static String placeholder(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "[Image omitted from this request: earlier images are not resent (request size limit). "
                    + "It is not visible now, do not describe it from memory. Read or attach it again if you need it.]";
        }
        return Strings.format("[Image result from {} omitted from this request: earlier images are not resent (request size limit). "
                + "It is not visible now, do not describe it from memory. Call {} again if you need it.]", toolName, toolName);
    }

    private static boolean isInlineImage(Content part) {
        return part != null && part.type == Content.ContentType.IMAGE_URL
                && part.imageUrl != null && part.imageUrl.url != null && part.imageUrl.url.startsWith("data:");
    }

    private static long decodedBytes(String dataUri) {
        var comma = dataUri.indexOf(',');
        if (comma < 0) return 0;
        return (dataUri.length() - comma - 1L) / 4 * 3;
    }

    private static boolean allKept(boolean[] kept) {
        for (var value : kept) {
            if (!value) return false;
        }
        return true;
    }

    private static Message copyWithContent(Message source, List<Content> content) {
        var copy = new Message();
        copy.role = source.role;
        copy.content = content;
        copy.reasoningContent = source.reasoningContent;
        copy.name = source.name;
        copy.toolCallId = source.toolCallId;
        copy.toolCalls = source.toolCalls;
        return copy;
    }

    private InlineImagePruner() {
    }

    private record ImageSlot(int messageIndex, int partIndex, long bytes) {
    }

    public record PruneResult(List<Message> messages, int prunedCount, long prunedBytes) {
    }
}
