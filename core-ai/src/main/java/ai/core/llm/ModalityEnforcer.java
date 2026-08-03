package ai.core.llm;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import core.framework.util.Strings;

import java.util.ArrayList;
import java.util.List;

/**
 * Request-time correctness guard: rewrites content parts the target model is known to reject
 * into text references, so a text-only model never receives image/file/video payloads.
 * Downgrades only on UNSUPPORTED; UNKNOWN passes through and is surfaced to the caller.
 * Idempotent: enforcing an already-enforced list returns it unchanged.
 *
 * @author Xander
 */
public final class ModalityEnforcer {

    public static EnforceResult enforce(List<Message> messages, String targetModel, ModelModalityRegistry registry) {
        if (messages == null || messages.isEmpty()) return new EnforceResult(messages, 0, false);
        var downgraded = 0;
        var unknownPresent = false;
        var changed = false;
        var result = new ArrayList<Message>(messages.size());
        for (var message : messages) {
            List<Content> replaced = null;
            if (message.content != null) {
                for (var i = 0; i < message.content.size(); i++) {
                    var part = message.content.get(i);
                    var modality = modalityOf(part);
                    if (modality == null || modality == InputModality.TEXT) continue;
                    var support = effectiveSupport(targetModel, modality, registry);
                    if (support == ModalitySupport.UNKNOWN) {
                        unknownPresent = true;
                        continue;
                    }
                    if (support == ModalitySupport.SUPPORTED) continue;
                    if (replaced == null) replaced = new ArrayList<>(message.content);
                    replaced.set(i, Content.of(placeholder(part)));
                    downgraded++;
                }
            }
            if (replaced == null) {
                result.add(message);
            } else {
                result.add(copyWithContent(message, List.copyOf(replaced)));
                changed = true;
            }
        }
        return new EnforceResult(changed ? List.copyOf(result) : messages, downgraded, unknownPresent);
    }

    private static ModalitySupport effectiveSupport(String model, InputModality modality, ModelModalityRegistry registry) {
        if (ModalityRuntimeOverrides.isMarkedUnsupported(model, modality)) return ModalitySupport.UNSUPPORTED;
        return registry.supports(model, modality);
    }

    private static InputModality modalityOf(Content part) {
        if (part == null || part.type == null) return null;
        return switch (part.type) {
            case TEXT -> InputModality.TEXT;
            case IMAGE_URL -> InputModality.IMAGE;
            case FILE -> InputModality.FILE;
            case VIDEO -> InputModality.VIDEO;
        };
    }

    private static String placeholder(Content part) {
        return switch (part.type) {
            case IMAGE_URL -> imagePlaceholder(part);
            case FILE -> filePlaceholder(part);
            case VIDEO -> videoPlaceholder(part);
            case TEXT -> throw new IllegalStateException("text parts are never downgraded");
        };
    }

    private static String imagePlaceholder(Content part) {
        var url = part.imageUrl == null ? null : part.imageUrl.url;
        if (url == null || url.startsWith("data:")) {
            return "[Image content omitted: current model does not support image input]";
        }
        return Strings.format("[Image attachment: {}] (not visible to current model; use caption_image to inspect)", url);
    }

    private static String filePlaceholder(Content part) {
        var file = part.file;
        if (file == null || file.fileData != null || file.fileId == null) {
            return "[File content omitted: current model does not support file input]";
        }
        return Strings.format("[File attachment: {}] (not visible to current model; use summarize_pdf to inspect)", file.fileId);
    }

    private static String videoPlaceholder(Content part) {
        var uri = part.video == null ? null : part.video.uri;
        if (uri == null) {
            return "[Video content omitted: current model does not support video input]";
        }
        return Strings.format("[Video attachment: {}] (not visible to current model)", uri);
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

    private ModalityEnforcer() {
    }

    public record EnforceResult(List<Message> messages, int downgradedCount, boolean unknownModalityPresent) {
    }
}
