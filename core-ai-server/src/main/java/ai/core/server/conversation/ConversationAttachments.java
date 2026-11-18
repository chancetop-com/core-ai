package ai.core.server.conversation;

import ai.core.api.server.session.SendMessageRequest;

import java.util.List;
import java.util.Locale;

/**
 * Attachment rules for a direct-conversation send. The desktop uploads straight to object storage with a
 * credential this server issued for one session, so the send only accepts a blob that lives where that
 * credential put it. Mirrors the web composer contract: images/videos/PDFs become multimodal context,
 * any other file is staged into the session sandbox.
 *
 * @author core-ai
 */
public final class ConversationAttachments {
    public static final int MAX_ATTACHMENTS = 10;

    private static final String MULTIMODAL = "multimodal";
    private static final String SANDBOX = "sandbox";
    private static final String MULTIMODAL_PREFIX = "ai/conv/";
    private static final String SANDBOX_PREFIX = "uploads/conv/";

    public static String container(String category, String multimodalContainer, String sandboxContainer) {
        return SANDBOX.equals(category) ? sandboxContainer : multimodalContainer;
    }

    /** Blob prefix this session's credential writes to; also the prefix a send may reference. */
    public static String blobPrefix(String category, String sessionId) {
        return (SANDBOX.equals(category) ? SANDBOX_PREFIX : MULTIMODAL_PREFIX) + sessionId + "/";
    }

    public static String category(String contentType) {
        if (contentType == null) return SANDBOX;
        var lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("image/") || lower.startsWith("video/") || "application/pdf".equals(lower)) return MULTIMODAL;
        return SANDBOX;
    }

    /**
     * @return an error message for the caller, or null when every attachment is acceptable
     */
    public static String validate(List<SendMessageRequest.SendMessageAttachment> attachments, String sessionId,
                                  String multimodalContainer, String sandboxContainer) {
        if (attachments == null || attachments.isEmpty()) return null;
        if (attachments.size() > MAX_ATTACHMENTS) return "at most " + MAX_ATTACHMENTS + " attachments are allowed";
        for (var attachment : attachments) {
            if (attachment == null) return "attachment is missing";
            var category = attachment.category;
            if (!MULTIMODAL.equals(category) && !SANDBOX.equals(category)) return "attachment category is invalid";
            if (attachment.container == null || !attachment.container.equals(container(category, multimodalContainer, sandboxContainer))) {
                return "attachment container is invalid";
            }
            if (attachment.blobName == null || !attachment.blobName.startsWith(blobPrefix(category, sessionId))) {
                return "attachment does not belong to this conversation";
            }
            if (attachment.fileName != null && attachment.fileName.length() > 255) return "attachment file name is too long";
            var typeError = validateType(attachment, category);
            if (typeError != null) return typeError;
        }
        return null;
    }

    private static String validateType(SendMessageRequest.SendMessageAttachment attachment, String category) {
        var contentType = attachment.contentType == null ? "" : attachment.contentType.toLowerCase(Locale.ROOT);
        var type = attachment.type == null ? "" : attachment.type.toUpperCase(Locale.ROOT);
        if ("IMAGE".equals(type)) return contentType.startsWith("image/") && MULTIMODAL.equals(category) ? null : "attachment type is invalid";
        if ("VIDEO".equals(type)) return contentType.startsWith("video/") && MULTIMODAL.equals(category) ? null : "attachment type is invalid";
        if ("PDF".equals(type)) return "application/pdf".equals(contentType) && MULTIMODAL.equals(category) ? null : "attachment type is invalid";
        if ("FILE".equals(type)) return SANDBOX.equals(category) ? null : "attachment type is invalid";
        return "attachment type is invalid";
    }

    /** Uppercases the type the way the session runtime expects; the web composer sends it that way too. */
    public static void normalize(List<SendMessageRequest.SendMessageAttachment> attachments) {
        if (attachments == null) return;
        for (var attachment : attachments) {
            if (attachment != null && attachment.type != null) attachment.type = attachment.type.toUpperCase(Locale.ROOT);
        }
    }

    /** Stable description of what the send carries, folded into the idempotency payload hash. */
    public static String fingerprint(List<SendMessageRequest.SendMessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        var builder = new StringBuilder();
        for (var attachment : attachments) {
            if (attachment == null) continue;
            builder.append(attachment.category).append('|')
                    .append(attachment.container).append('|')
                    .append(attachment.blobName).append('|')
                    .append(attachment.fileName).append('|')
                    .append(attachment.contentType).append(';');
        }
        return builder.toString();
    }

    private ConversationAttachments() {
    }
}
