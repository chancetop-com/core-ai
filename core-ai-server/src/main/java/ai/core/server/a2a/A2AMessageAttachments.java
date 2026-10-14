package ai.core.server.a2a;

import ai.core.agent.AttachedContent;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.Part;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.messaging.ChatMessageAttachments;
import ai.core.server.sandbox.SandboxService;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The attachment side of an inbound A2A message. File parts get the treatment the equivalent chat message
 * would get — a URL the agent can fetch, a sandbox copy it can work on, and, for images, a model attachment —
 * so a hub run delivers the same message a chat composer would produce. A part carrying platform storage
 * coordinates is staged from the blob; any other URL is handed to the agent as-is.
 *
 * @author stephen
 */
public class A2AMessageAttachments {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2AMessageAttachments.class);
    private static final String TYPE_IMAGE = "IMAGE";
    private static final String TYPE_VIDEO = "VIDEO";
    private static final String TYPE_PDF = "PDF";
    private static final String METADATA_CONTAINER = "container";
    private static final String METADATA_BLOB_NAME = "blobName";

    @Inject
    SandboxService sandboxService;
    @Inject
    ObjectStorageServiceResolver objectStorageResolver;
    @Inject
    SessionAttachmentRefRepository attachmentRepository;

    public Delivered deliver(String sessionId, String userId, Message message, String userText) {
        var text = new StringBuilder(userText == null ? "" : userText);
        var stageable = new ArrayList<Map<String, String>>();
        var inline = new ArrayList<Map<String, String>>();
        if (message != null && message.parts != null) {
            for (var part : message.parts) {
                collect(part, text, stageable, inline);
            }
        }
        if (stageable.isEmpty()) return new Delivered(text.toString(), null);

        var chatAttachments = new ChatMessageAttachments(sandboxService, objectStorageResolver, attachmentRepository);
        var contents = inline.isEmpty() ? null : contents(chatAttachments, inline, sessionId, userId);
        var staged = chatAttachments.stageInto(sessionId, userId, payload(stageable));
        var delivered = chatAttachments.appendSandboxPaths(text.toString(), staged);
        return new Delivered(chatAttachments.appendVideoHints(delivered, contents), contents);
    }

    private void collect(Part part, StringBuilder text, List<Map<String, String>> stageable, List<Map<String, String>> inline) {
        if (part == null || part.url == null || part.url.isBlank()) return;
        var type = partType(part);
        var entry = stagingEntry(part, type);
        if (entry == null) {
            appendUrl(text, part.url);
            return;
        }
        stageable.add(entry);
        if (isInline(type, entry)) {
            inline.add(entry);
            if (!TYPE_VIDEO.equals(type)) appendUrl(text, part.url);
        }
    }

    /** An entry the sandbox machinery can stage: the file must live in this platform's own storage. */
    private Map<String, String> stagingEntry(Part part, String type) {
        var container = metadata(part, METADATA_CONTAINER);
        var blobName = metadata(part, METADATA_BLOB_NAME);
        if (container == null || blobName == null) return null;
        var entry = new LinkedHashMap<String, String>();
        entry.put("type", type != null ? type : "FILE");
        entry.put("fileName", part.filename != null && !part.filename.isBlank() ? part.filename : blobName);
        entry.put("contentType", part.mediaType);
        entry.put("container", container);
        entry.put("blobName", blobName);
        entry.put("url", part.url);
        return entry;
    }

    /** Only the multimodal container is accepted by the model-attachment helpers. */
    private boolean isInline(String type, Map<String, String> entry) {
        if (!TYPE_IMAGE.equals(type) && !TYPE_VIDEO.equals(type) && !TYPE_PDF.equals(type)) return false;
        return objectStorageResolver.multimodalContainer().equals(entry.get("container"))
                && entry.get("blobName").startsWith("ai/");
    }

    private List<AttachedContent> contents(ChatMessageAttachments chatAttachments, List<Map<String, String>> inline,
                                           String sessionId, String userId) {
        try {
            return chatAttachments.contents(payload(inline), sessionId, userId);
        } catch (RuntimeException e) {
            // a malformed attachment must not fail the run: the URLs still reach the agent
            LOGGER.warn("attachment contents skipped, sessionId={}, reason={}", sessionId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> payload(List<Map<String, String>> entries) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("multimodalAttachments", entries);
        return payload;
    }

    private String partType(Part part) {
        var mediaType = part.mediaType;
        if (mediaType == null) return null;
        if (mediaType.startsWith("image/")) return TYPE_IMAGE;
        if (mediaType.startsWith("video/")) return TYPE_VIDEO;
        if (mediaType.startsWith("application/pdf")) return TYPE_PDF;
        return null;
    }

    private String metadata(Part part, String key) {
        if (part.metadata == null) return null;
        var value = part.metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private void appendUrl(StringBuilder text, String url) {
        if (!text.isEmpty()) text.append("\n\n");
        text.append(url);
    }

    public record Delivered(String text, List<AttachedContent> contents) {
    }
}
