package ai.core.llm;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.utils.JsonUtil;
import core.framework.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request-time size guard for stateless chat completion calls: the whole history is resent on every
 * turn, so the inline base64 images accumulated by a long agent session eventually push the request
 * body past the upstream limit (DeepSeek answers HTTP 413 Request Entity Too Large).
 * <p>
 * The budget is applied to user attachment sets rather than to images one by one: a message that
 * carried several images is the unit the model has to see whole, never split by the count budget,
 * and the set of the live turn is kept even when it alone exceeds the budget — hiding half of the
 * images the user just sent is worse than one oversized request, which the 413 retry handles by
 * dropping every inline image. Tool images are deliberately not part of a set: their number is
 * open-ended (a screenshot or render loop), so they stay individually budgeted, newest first.
 * <p>
 * The live attachment set is kept whole; older attachment sets survive newest-first while the
 * count and byte budgets hold, then tool images fill what is left, newest-first (the newest tool
 * image always survives). Whatever does not fit becomes a text placeholder that names the source
 * (the tool argument that produced it, or the attachment label) so the agent can fetch it again.
 * Placeholder text is derived from the message only, never from a counter, so the prompt cache
 * prefix stays valid across turns.
 * Every eviction rewrites one message in the middle of the history, which invalidates the provider's
 * prompt cache from that position to the end of the request — on a long agent session one evicted
 * keyframe costs far more than the bytes it saves. The count budget is therefore generous and the
 * byte budget is what actually bounds the request; an image handed to the model twice (a file the
 * agent read again) counts once, so a re-read never evicts an unrelated image.
 * Inline files (base64 PDFs) are out of scope, they are only ever attached by the user.
 *
 * @author stephen
 */
public final class InlineImagePruner {
    public static final int DEFAULT_MAX_IMAGES = 16;
    public static final long DEFAULT_MAX_BYTES = 12L * 1024 * 1024;
    /** How many newer user messages still leave the previous attachment set protected. */
    private static final int PROTECTED_RECENT_USER_TURNS = 1;
    private static final List<String> SOURCE_ARGUMENT_KEYS = List.of(
            "file_path", "path", "image_path", "sandbox_path", "url", "image_url", "source", "attachment");

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
        var kept = selectKept(messages, slots, maxImages, maxBytes);
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
                slots.add(new ImageSlot(messageIndex, partIndex, part.imageUrl.url));
            }
        }
        return slots;
    }

    /**
     * Keeps the newest user attachment set whole, then older attachment sets newest-first and
     * finally the tool images. A user attachment set is the unit the model has to see whole: a
     * message that carried several images is never split by the count budget, and the newest one is
     * kept even when it alone exceeds the budget. Tool images stay individually budgeted — a turn
     * that read twenty screenshots must not pin all twenty.
     */
    @SuppressFBWarnings(value = "SUA_SUSPICIOUS_UNINITIALIZED_ARRAY",
            justification = "boolean flags default to false, which is the intended initial state")
    private static boolean[] selectKept(List<Message> messages, List<ImageSlot> slots, int maxImages, long maxBytes) {
        var kept = new boolean[slots.size()];
        if (maxImages == 0) return kept;
        var userGroups = userImageGroups(messages, slots);
        var toolSlots = toolImageSlots(messages, slots);
        var keptPayloads = new HashSet<String>();
        long bytes = 0;
        var count = 0;
        var maxCount = maxImages < 0 ? Integer.MAX_VALUE : maxImages;

        var lastGroupIndex = userGroups.size() - 1;
        if (lastGroupIndex >= 0 && isRecent(messages, slots, userGroups.get(lastGroupIndex))) {
            var newest = freshSlots(userGroups.get(lastGroupIndex), slots, keptPayloads);
            for (var slotIndex : newest) {
                keep(slotIndex, slots, kept, keptPayloads);
                count++;
                bytes += slots.get(slotIndex).bytes();
            }
            lastGroupIndex--;
        }
        for (var groupIndex = lastGroupIndex; groupIndex >= 0; groupIndex--) {
            var fresh = freshSlots(userGroups.get(groupIndex), slots, keptPayloads);
            if (fresh.isEmpty()) continue;
            if (count + fresh.size() > maxCount) continue;
            if (maxBytes > 0 && bytes + bytesOf(fresh, slots) > maxBytes) continue;
            for (var slotIndex : fresh) {
                keep(slotIndex, slots, kept, keptPayloads);
                count++;
                bytes += slots.get(slotIndex).bytes();
            }
        }
        for (var i = toolSlots.size() - 1; i >= 0; i--) {
            var slotIndex = toolSlots.get(i);
            var slot = slots.get(slotIndex);
            // the newest tool image is resent even when it alone exceeds the budget: hiding what the
            // agent just looked at makes it read the same file again, and a request that is still too
            // large is recovered by the 413 retry instead
            var newest = i == toolSlots.size() - 1;
            if (!newest && (count >= maxCount || maxBytes > 0 && bytes + slot.bytes() > maxBytes)) continue;
            if (!keptPayloads.add(slot.payload())) continue;
            kept[slotIndex] = true;
            count++;
            bytes += slot.bytes();
        }
        return kept;
    }

    private static List<Integer> freshSlots(List<Integer> group, List<ImageSlot> slots, Set<String> keptPayloads) {
        var fresh = new ArrayList<Integer>();
        var freshPayloads = new HashSet<String>();
        for (var i = group.size() - 1; i >= 0; i--) {
            var slotIndex = group.get(i);
            var payload = slots.get(slotIndex).payload();
            if (keptPayloads.contains(payload) || !freshPayloads.add(payload)) continue;
            fresh.add(slotIndex);
        }
        return fresh;
    }

    private static long bytesOf(List<Integer> slotIndexes, List<ImageSlot> slots) {
        long bytes = 0;
        for (var slotIndex : slotIndexes) {
            bytes += slots.get(slotIndex).bytes();
        }
        return bytes;
    }

    private static void keep(int slotIndex, List<ImageSlot> slots, boolean[] kept, Set<String> keptPayloads) {
        kept[slotIndex] = true;
        keptPayloads.add(slots.get(slotIndex).payload());
    }

    /**
     * One group per user message that carried attachments. Tool images are deliberately not folded
     * into the group: their number is open-ended (a screenshot loop) and the byte budget, not the
     * user's intent, should decide how many of them survive.
     */
    private static List<List<Integer>> userImageGroups(List<Message> messages, List<ImageSlot> slots) {
        Map<Integer, List<Integer>> byMessage = new LinkedHashMap<>();
        for (var i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            if (messages.get(slot.messageIndex()).role != RoleType.USER) continue;
            byMessage.computeIfAbsent(slot.messageIndex(), ignored -> new ArrayList<>()).add(i);
        }
        return new ArrayList<>(byMessage.values());
    }

    /**
     * The newest attachment set is only exempt while it is still part of the live conversation:
     * the user's latest turn or the one immediately after it. Once the conversation has moved on,
     * the set falls back to the ordinary whole-set budget instead of pinning every later request.
     */
    private static boolean isRecent(List<Message> messages, List<ImageSlot> slots, List<Integer> group) {
        var userMessageIndex = slots.get(group.getFirst()).messageIndex();
        var newerUserMessages = 0;
        for (var i = userMessageIndex + 1; i < messages.size(); i++) {
            if (messages.get(i).role == RoleType.USER) newerUserMessages++;
        }
        return newerUserMessages <= PROTECTED_RECENT_USER_TURNS;
    }

    private static List<Integer> toolImageSlots(List<Message> messages, List<ImageSlot> slots) {
        var toolSlots = new ArrayList<Integer>();
        for (var i = 0; i < slots.size(); i++) {
            if (messages.get(slots.get(i).messageIndex()).role != RoleType.USER) toolSlots.add(i);
        }
        return toolSlots;
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
            content.set(slot.partIndex(), Content.of(placeholder(messages, slot)));
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

    private static String placeholder(List<Message> messages, ImageSlot slot) {
        var message = messages.get(slot.messageIndex());
        if (message.role == RoleType.TOOL) {
            var toolName = message.name == null || message.name.isBlank() ? "the tool" : message.name;
            var source = toolSource(messages, slot.messageIndex(), message.toolCallId);
            var sourceHint = source == null ? "" : " Source: " + source + ".";
            return Strings.format("[Image result from {} omitted from this request: earlier images are not resent (request size limit). "
                    + "It is not visible now, do not describe it from memory.{} Call {} again if you need it.]", toolName, sourceHint, toolName);
        }
        var label = imageLabel(message, slot.partIndex());
        if (label != null) {
            return label + " omitted from this request: earlier images are not resent (request size limit). "
                    + "It is not visible now, do not describe it from memory. Attach it again if you need it.";
        }
        return "[Image omitted from this request: earlier images are not resent (request size limit). "
                + "It is not visible now, do not describe it from memory. Read or attach it again if you need it.]";
    }

    /**
     * The label {@code [Image 1: photo.png]} is placed directly before its image part (see
     * {@code AgentHelper.buildUserMessage}), so a pruned attachment still tells the model which
     * picture went away.
     */
    private static String imageLabel(Message message, int imagePartIndex) {
        if (message.content == null || imagePartIndex <= 0) return null;
        var part = message.content.get(imagePartIndex - 1);
        if (part == null || part.type != Content.ContentType.TEXT || part.text == null) return null;
        var text = part.text.strip();
        return text.startsWith("[Image") && text.endsWith("]") ? text : null;
    }

    /**
     * The tool call arguments still carry the path or URL the image came from (read_file's
     * {@code file_path}, a media tool's {@code url}, ...), which is exactly what the model needs to
     * fetch it again after the image part was replaced.
     */
    private static String toolSource(List<Message> messages, int messageIndex, String toolCallId) {
        if (toolCallId == null) return null;
        for (var i = messageIndex - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.role != RoleType.ASSISTANT || message.toolCalls == null) continue;
            for (var call : message.toolCalls) {
                if (call != null && call.function != null && toolCallId.equals(call.id)) {
                    return argumentSource(call.function.arguments);
                }
            }
        }
        return null;
    }

    private static String argumentSource(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        var text = arguments.strip();
        if (!text.startsWith("{")) return null;
        try {
            var args = JsonUtil.toMap(text);
            for (var key : SOURCE_ARGUMENT_KEYS) {
                if (args.get(key) instanceof String value && !value.isBlank() && value.length() <= 2048
                        && !value.startsWith("data:")) {
                    return value;
                }
            }
        } catch (RuntimeException e) {
            // arguments are not a JSON object, no recoverable source to show
            return null;
        }
        return null;
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

    private record ImageSlot(int messageIndex, int partIndex, String payload) {
        long bytes() {
            return decodedBytes(payload);
        }
    }

    public record PruneResult(List<Message> messages, int prunedCount, long prunedBytes) {
    }
}
