package ai.core.media;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Turns the refs of one request into the provider's wire form. This is the only place that reads
 * media bytes on the request path; the input request and the agent's conversation state are never
 * modified. A missing object degrades to a text placeholder, it never fails the turn.
 *
 * @author core-ai
 */
public final class MediaHydrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaHydrator.class);

    public static List<Message> hydrate(List<Message> messages, SessionMediaStore store, SessionMediaKey key) {
        if (!containsRefs(messages)) return messages;
        var hydrated = new ArrayList<Message>(messages.size());
        for (var message : messages) {
            hydrated.add(hydrate(message, store, key));
        }
        return List.copyOf(hydrated);
    }

    /** Returns a wire copy of the request with every ref replaced; the input request stays untouched. */
    public static CompletionRequest hydrate(CompletionRequest request) {
        if (!containsRefs(request.messages)) return request;
        return request.withMessages(hydrate(request.messages, request.getMediaStore(), request.getMediaKey()));
    }

    private static Message hydrate(Message message, SessionMediaStore store, SessionMediaKey key) {
        var content = message.content;
        if (content == null || content.isEmpty()) return message;
        List<Content> replaced = null;
        for (var i = 0; i < content.size(); i++) {
            var part = content.get(i);
            if (part == null || part.type != Content.ContentType.IMAGE_REF) continue;
            if (replaced == null) replaced = new ArrayList<>(content);
            replaced.set(i, materialize(part, store, key));
        }
        if (replaced == null) return message;
        return copyWithContent(message, List.copyOf(replaced));
    }

    public static boolean containsRefs(List<Message> messages) {
        if (messages == null) return false;
        for (var message : messages) {
            if (message.content == null) continue;
            for (var part : message.content) {
                if (part != null && part.type == Content.ContentType.IMAGE_REF) return true;
            }
        }
        return false;
    }

    /** Negative test hook: a provider must never receive a ref, it cannot encode one. */
    public static void assertNoUnhydratedRefs(List<Message> messages) {
        if (!containsRefs(messages)) return;
        throw new IllegalStateException("image ref was not hydrated before encoding the provider request");
    }

    private static Content materialize(Content part, SessionMediaStore store, SessionMediaKey key) {
        var ref = part.imageRef;
        if (ref == null) return Content.of("[Image omitted: the media reference is missing.]");
        if (store == null || key == null) {
            LOGGER.warn("image ref cannot be hydrated without a session media store, mediaId={}", ref.id);
            return Content.of(placeholder(ref));
        }
        Optional<byte[]> bytes;
        try {
            bytes = store.read(key, ref);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to hydrate image ref, mediaId={}, error={}", ref.id, e.getMessage());
            bytes = Optional.empty();
        }
        if (bytes.isEmpty()) {
            LOGGER.warn("image ref is missing from the session media store, mediaId={}", ref.id);
            return Content.of(placeholder(ref));
        }
        return Content.of(Content.ImageUrl.of(dataUri(ref, bytes.get()), mediaType(ref)));
    }

    public static String dataUri(MediaRef ref, byte[] bytes) {
        return "data:" + mediaType(ref) + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String mediaType(MediaRef ref) {
        if (ref.mediaType != null && !ref.mediaType.isBlank()) return ref.mediaType;
        if (ref.format != null && !ref.format.isBlank()) {
            return ref.format.contains("/") ? ref.format : "image/" + ref.format;
        }
        return "image/png";
    }

    private static String placeholder(MediaRef ref) {
        return "[Image " + ref.label() + " omitted from this request: the session media store no longer has it. "
                + "It is not visible now, do not describe it from memory. Read or attach it again if you need it.]";
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

    private MediaHydrator() {
    }
}
