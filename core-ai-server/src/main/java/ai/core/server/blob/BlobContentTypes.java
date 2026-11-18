package ai.core.server.blob;

import java.util.Map;

/**
 * Content-type to storage-extension mapping shared by every upload-credential issuer, so a browser
 * upload and a direct-conversation upload land under the same predictable blob names.
 *
 * @author core-ai
 */
public final class BlobContentTypes {
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            // images
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/svg+xml", ".svg"),
            // videos
            Map.entry("video/mp4", ".mp4"),
            Map.entry("video/webm", ".webm"),
            Map.entry("video/quicktime", ".mov"),
            Map.entry("video/x-msvideo", ".avi"),
            // documents
            Map.entry("application/pdf", ".pdf"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
            // text / code / data
            Map.entry("text/plain", ".txt"),
            Map.entry("text/markdown", ".md"),
            Map.entry("text/csv", ".csv"),
            Map.entry("text/html", ".html"),
            Map.entry("text/css", ".css"),
            Map.entry("text/javascript", ".js"),
            Map.entry("application/javascript", ".js"),
            Map.entry("text/xml", ".xml"),
            Map.entry("application/xml", ".xml"),
            Map.entry("application/json", ".json"),
            Map.entry("application/x-yaml", ".yaml"),
            Map.entry("text/yaml", ".yaml"),
            // archives
            Map.entry("application/zip", ".zip"),
            Map.entry("application/x-tar", ".tar"),
            Map.entry("application/gzip", ".gz"),
            Map.entry("application/x-gzip", ".gz"));

    public static String extensionOf(String contentType) {
        if (contentType == null) return "";
        return EXTENSIONS.getOrDefault(contentType.toLowerCase(java.util.Locale.ROOT), "");
    }

    private BlobContentTypes() {
    }
}
