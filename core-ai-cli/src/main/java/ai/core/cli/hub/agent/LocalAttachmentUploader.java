package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubAttachment;
import ai.core.api.server.blob.BlobUploadCredentialView;
import ai.core.cli.http.RemoteApiClient;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import ai.core.utils.JsonUtil;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Uploads a local file for a hub run: the bytes go to the platform's object storage through the same
 * pre-signed credential flow the web composer uses, and the resulting URL plus storage coordinates
 * become the attachment. Images, PDFs and videos land in the multimodal container; any other file
 * goes to the sandbox container, matching where the chat composer would put it.
 *
 * @author stephen
 */
public class LocalAttachmentUploader {
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".mp4", "video/mp4"),
            Map.entry(".webm", "video/webm"),
            Map.entry(".mov", "video/quicktime"),
            Map.entry(".avi", "video/x-msvideo"),
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry(".txt", "text/plain"),
            Map.entry(".md", "text/markdown"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".json", "application/json"),
            Map.entry(".xml", "application/xml"),
            Map.entry(".yaml", "application/x-yaml"),
            Map.entry(".yml", "application/x-yaml"),
            Map.entry(".zip", "application/zip"),
            Map.entry(".tar", "application/x-tar"),
            Map.entry(".gz", "application/gzip"));

    private final RemoteApiClient apiClient;

    public LocalAttachmentUploader(String serverUrl, String apiKey, boolean insecure) {
        this.apiClient = new RemoteApiClient(serverUrl, apiKey, UPLOAD_TIMEOUT, Map.of("X-Core-AI-Client", "cli"), insecure);
    }

    public AgentHubAttachment upload(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new HubCliError(HubExitCodes.USAGE, "attachment file not found: " + file);
        }
        var name = fileName(file);
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.ofFile(file);
        } catch (FileNotFoundException e) {
            throw new HubCliError(HubExitCodes.TOOL_ERROR, "attachment upload failed: " + e.getMessage(), e);
        }
        return upload(body, name, contentType(name));
    }

    /**
     * In-memory variant for callers that hold the bytes instead of a path, e.g. a tool forwarding a
     * chat attachment that arrived base64-encoded.
     */
    public AgentHubAttachment upload(byte[] data, String fileName, String contentType) {
        var name = fileName == null || fileName.isBlank() ? "attachment" : fileName;
        var mediaType = contentType == null || contentType.isBlank() ? OCTET_STREAM : contentType;
        return upload(HttpRequest.BodyPublishers.ofByteArray(data == null ? new byte[0] : data), name, mediaType);
    }

    private AgentHubAttachment upload(HttpRequest.BodyPublisher body, String fileName, String contentType) {
        var credential = credential(contentType, category(contentType));
        put(credential.uploadUrl, body, fileName, contentType);
        var attachment = new AgentHubAttachment();
        attachment.url = credential.blobUrl;
        attachment.type = type(contentType);
        attachment.fileName = fileName;
        attachment.contentType = contentType;
        attachment.container = credential.container;
        attachment.blobName = credential.blobName;
        return attachment;
    }

    private String category(String contentType) {
        return isMultimodal(contentType) ? "multimodal" : "sandbox";
    }

    private BlobUploadCredentialView credential(String contentType, String category) {
        var path = "/api/blob/upload-credential?content_type=" + encode(contentType) + "&category=" + category;
        return JsonUtil.fromJson(BlobUploadCredentialView.class, apiClient.getRequired(path));
    }

    private void put(String uploadUrl, HttpRequest.BodyPublisher body, String fileName, String contentType) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(UPLOAD_TIMEOUT)
                    .header("x-ms-blob-type", "BlockBlob")
                    .header("x-ms-blob-content-type", contentType)
                    .PUT(body)
                    .build();
            var response = apiClient.httpClient().send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                throw new HubCliError(HubExitCodes.TOOL_ERROR, "attachment upload failed: status=" + response.statusCode() + ", file=" + fileName);
            }
        } catch (IOException e) {
            throw new HubCliError(HubExitCodes.TOOL_ERROR, "attachment upload failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HubCliError(HubExitCodes.TOOL_ERROR, "attachment upload interrupted", e);
        }
    }

    private String contentType(String fileName) {
        var name = fileName.toLowerCase(Locale.ROOT);
        var dot = name.lastIndexOf('.');
        if (dot < 0) return OCTET_STREAM;
        return CONTENT_TYPES.getOrDefault(name.substring(dot), OCTET_STREAM);
    }

    private String fileName(Path file) {
        var name = file.getFileName();
        return name == null ? "" : name.toString();
    }

    private boolean isMultimodal(String contentType) {
        return contentType.startsWith("image/") || contentType.startsWith("video/") || "application/pdf".equals(contentType);
    }

    private String type(String contentType) {
        if (contentType.startsWith("image/")) return "image";
        if (contentType.startsWith("video/")) return "video";
        if ("application/pdf".equals(contentType)) return "pdf";
        return "file";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
