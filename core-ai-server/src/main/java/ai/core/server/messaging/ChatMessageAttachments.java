package ai.core.server.messaging;

import ai.core.agent.AttachedContent;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.sandbox.PendingFile;
import ai.core.server.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The attachment side of an inbound chat message: the multimodal entries the web layer put into the command
 * payload become model attachments, a sandbox copy the agent can work on locally, and the hints that tell it
 * where both are.
 *
 * @author stephen
 */
class ChatMessageAttachments {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatMessageAttachments.class);
    private static final String SANDBOX_HINT = "\n\n[Attachments are also staged in the sandbox at ";

    private final SandboxService sandboxService;
    private final ObjectStorageServiceResolver objectStorageResolver;
    private final SessionAttachmentRefRepository attachmentRepository;

    ChatMessageAttachments(SandboxService sandboxService, ObjectStorageServiceResolver objectStorageResolver,
                           SessionAttachmentRefRepository attachmentRepository) {
        this.sandboxService = sandboxService;
        this.objectStorageResolver = objectStorageResolver;
        this.attachmentRepository = attachmentRepository;
    }

    List<AttachedContent> contents(Map<String, Object> payload, String sessionId, String userId) {
        var attachments = attachments(payload);
        if (attachments.isEmpty()) return null;
        var storageService = objectStorageResolver.resolve();
        if (storageService == null) throw new IllegalStateException("object storage is not configured");
        var contents = new ArrayList<AttachedContent>(attachments.size());
        for (var attachment : attachments) {
            var type = (String) attachment.get("type");
            if ("VIDEO".equals(type)) {
                contents.add(videoContent(attachment, sessionId, userId, storageService));
            } else if (type == null || "IMAGE".equals(type)) {
                // a missing type is a legacy payload: images were the only non-video kind the web layer sent
                contents.add(imageContent(attachment, storageService));
            }
        }
        return contents;
    }

    /**
     * Copies the message's attachments into the sandbox so the agent can process the uploaded bytes itself —
     * convert a container a model refuses, crop, extract a frame, read a PDF — and hand the result back to a
     * media tool. Best effort: the attachment always also arrives as a URL.
     *
     * @return the sandbox paths staged, for {@link #appendSandboxPaths}
     */
    List<String> stageInto(String sessionId, String userId, Map<String, Object> payload) {
        if (sandboxService == null) return List.of();
        var attachments = attachments(payload);
        if (attachments.isEmpty()) return List.of();
        var files = new ArrayList<PendingFile>(attachments.size());
        for (var attachment : attachments) {
            var container = (String) attachment.get("container");
            var blobName = (String) attachment.get("blobName");
            if (container == null || blobName == null) continue;
            files.add(new PendingFile((String) attachment.get("fileName"), container, blobName,
                    (String) attachment.get("contentType")));
        }
        if (files.isEmpty()) return List.of();
        try {
            return sandboxService.stageAttachments(sessionId, userId, files);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to stage attachments into the sandbox: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /** Tells the agent the staged copy exists and how to hand a locally fixed file back to a media tool. */
    String appendSandboxPaths(String message, List<String> stagedPaths) {
        if (stagedPaths == null || stagedPaths.isEmpty()) return message;
        var hint = SANDBOX_HINT + String.join(", ", stagedPaths)
                + " — process them there (ffmpeg, python, …) when a model cannot take the uploaded file as-is,"
                + " and pass the result back with {\"sandbox_path\": \"<path>\"}]";
        return message == null || message.isBlank() ? hint.strip() : message + hint;
    }

    String appendVideoHints(String message, List<AttachedContent> attachedContents) {
        if (attachedContents == null || attachedContents.isEmpty()) return message;
        var hints = new ArrayList<String>();
        for (var content : attachedContents) {
            if (content.type != AttachedContent.AttachedContentType.VIDEO) continue;
            var name = content.filename != null ? content.filename : "video";
            hints.add("[Video attachment: " + name + "]\nreference: " + content.url);
        }
        if (hints.isEmpty()) return message;
        var text = String.join("\n", hints);
        return message == null || message.isBlank() ? text : message + "\n\n" + text;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attachments(Map<String, Object> payload) {
        var attachments = (List<Map<String, Object>>) payload.get("multimodalAttachments");
        if (attachments == null || attachments.isEmpty()) {
            attachments = (List<Map<String, Object>>) payload.get("imageAttachments");
        }
        return attachments == null ? List.of() : attachments;
    }

    private AttachedContent videoContent(Map<String, Object> attachment, String sessionId, String userId,
                                         ObjectStorageService storageService) {
        var container = (String) attachment.get("container");
        var blobName = (String) attachment.get("blobName");
        if (!validVideoAttachment(container, blobName)) throw new IllegalArgumentException("invalid video attachment");

        var reference = new SessionAttachmentRef();
        reference.id = "video_" + UUID.randomUUID();
        reference.sessionId = sessionId;
        reference.userId = userId;
        reference.container = container;
        reference.blobName = blobName;
        reference.fileName = (String) attachment.get("fileName");
        reference.kind = SessionAttachmentRef.KIND_VIDEO;
        reference.createdAt = ZonedDateTime.now();
        var metadata = storageService.headObject(container, blobName);
        reference.sourceETag = metadata.etag();
        reference.sourceSizeBytes = metadata.sizeBytes();
        reference.contentType = resolveVideoContentType(metadata.contentType(), (String) attachment.get("contentType"));
        attachmentRepository.insert(reference);
        return AttachedContent.ofReference(reference.id, reference.contentType, reference.fileName);
    }

    private AttachedContent imageContent(Map<String, Object> attachment, ObjectStorageService storageService) {
        var container = (String) attachment.get("container");
        var blobName = (String) attachment.get("blobName");
        var contentType = (String) attachment.get("contentType");
        if (!validImageAttachment(container, blobName, contentType)) throw new IllegalArgumentException("invalid image attachment");

        var bytes = storageService.downloadObject(container, blobName);
        var content = AttachedContent.ofBase64(Base64.getEncoder().encodeToString(bytes), contentType,
                AttachedContent.AttachedContentType.IMAGE, (String) attachment.get("fileName"));
        content.url = (String) attachment.get("url");
        return content;
    }

    private String resolveVideoContentType(String storageContentType, String clientContentType) {
        if (storageContentType != null && storageContentType.startsWith("video/")) return storageContentType;
        if (clientContentType != null && clientContentType.startsWith("video/")) return clientContentType;
        return "video/mp4";
    }

    private boolean validImageAttachment(String container, String blobName, String contentType) {
        return validObjectAttachment(container, blobName, contentType) && contentType.startsWith("image/");
    }

    private boolean validVideoAttachment(String container, String blobName) {
        return container != null && blobName != null
                && container.equals(objectStorageResolver.multimodalContainer())
                && blobName.startsWith("ai/");
    }

    private boolean validObjectAttachment(String container, String blobName, String contentType) {
        return container != null && blobName != null && contentType != null
                && container.equals(objectStorageResolver.multimodalContainer())
                && blobName.startsWith("ai/");
    }
}
