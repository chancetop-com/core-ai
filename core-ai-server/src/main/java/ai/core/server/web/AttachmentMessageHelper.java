package ai.core.server.web;

import ai.core.api.server.session.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
class AttachmentMessageHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentMessageHelper.class);

    /** Collect pending file metadata for sandbox upload, returned as serializable maps
     *  to be embedded in the command payload so the processing pod has the data. */
    static List<Map<String, String>> collectPendingFiles(String sessionId, SendMessageRequest request) {
        LOGGER.info("[ENQUEUE] collectPendingFiles called, sessionId={}, hasAttachments={}, count={}",
                sessionId, request.attachments != null, request.attachments != null ? request.attachments.size() : 0);
        if (request.attachments == null || request.attachments.isEmpty()) return null;
        var result = new ArrayList<Map<String, String>>();
        for (var att : request.attachments) {
            LOGGER.info("[ENQUEUE] attachment: category={}, fileName={}, container={}, blobName={}, url={}",
                    att.category, att.fileName, att.container, att.blobName, att.url);
            if ("sandbox".equals(att.category)) {
                if (att.container == null || att.blobName == null) {
                    LOGGER.warn("[ENQUEUE] sandbox attachment missing container/blobName, sessionId={}, fileName={}",
                            sessionId, att.fileName);
                    continue;
                }
                result.add(Map.of(
                        "fileName", att.fileName != null ? att.fileName : att.blobName,
                        "container", att.container,
                        "blobName", att.blobName));
                continue;
            }
            if ("multimodal".equals(att.category)) {
                LOGGER.info("[ENQUEUE] multimodal attachment, skipping sandbox upload, fileName={}", att.fileName);
                continue;
            }
            // Handle attachments with blob storage URL (front-end sends url instead of container/blobName)
            if (att.url != null) {
                var blobInfo = parseBlobUrl(att.url);
                if (blobInfo != null) {
                    LOGGER.info("[ENQUEUE] parsed blob URL: container={}, blobName={}", blobInfo.container, blobInfo.blobName);
                    result.add(Map.of(
                            "fileName", att.fileName != null ? att.fileName : blobInfo.fileName(),
                            "container", blobInfo.container(),
                            "blobName", blobInfo.blobName()));
                    continue;
                }
                LOGGER.info("[ENQUEUE] url is not a blob storage URL, skipping sandbox upload, url={}", att.url);
            }
        }
        return result.isEmpty() ? null : result;
    }

    static String buildMessageWithAttachments(SendMessageRequest request) {
        if (request.attachments == null || request.attachments.isEmpty()) {
            return request.message;
        }
        var sandboxPaths = new ArrayList<String>();
        var urlParts = new ArrayList<String>();
        for (var att : request.attachments) {
            if ("multimodal".equals(att.category) && att.url != null) {
                // multimodal images/PDFs: pass blob URL directly so AI can use caption_image/summarize_pdf
                urlParts.add(att.url);
            } else if ("sandbox".equals(att.category)) {
                var name = att.fileName != null ? att.fileName : att.blobName;
                sandboxPaths.add("/tmp/" + name);
            } else if (att.url != null && parseBlobUrl(att.url) != null) {
                var blobInfo = parseBlobUrl(att.url);
                sandboxPaths.add("/tmp/" + (att.fileName != null ? att.fileName : blobInfo.fileName()));
            } else if (att.url != null) {
                urlParts.add(att.url);
            }
        }
        var parts = new ArrayList<String>();
        urlParts.forEach(parts::add);
        for (var path : sandboxPaths) {
            parts.add("[File uploaded to sandbox: " + path + "]");
        }
        if (parts.isEmpty()) return request.message;
        var attachmentText = String.join("\n", parts);
        if (request.message == null || request.message.isBlank()) return attachmentText;
        return request.message + "\n\n" + attachmentText;
    }

    private static BlobInfo parseBlobUrl(String url) {
        // Match: https://{account}.blob.core.windows.net/{container}/{blobPath}
        var prefix = "blob.core.windows.net/";
        var idx = url.indexOf(prefix);
        if (idx < 0) return null;
        var path = url.substring(idx + prefix.length());
        var firstSlash = path.indexOf('/');
        if (firstSlash < 0) return null;
        var container = path.substring(0, firstSlash);
        var blobName = path.substring(firstSlash + 1);
        if (container.isEmpty() || blobName.isEmpty()) return null;
        return new BlobInfo(container, blobName);
    }

    record BlobInfo(String container, String blobName) {
        String fileName() {
            var name = blobName;
            var lastSlash = name.lastIndexOf('/');
            if (lastSlash >= 0) name = name.substring(lastSlash + 1);
            return name;
        }
    }
}
