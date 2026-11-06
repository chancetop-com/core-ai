package ai.core.cli.tool;

import ai.core.api.server.agenthub.AgentHubAttachment;
import ai.core.api.server.hubmedia.HubMediaUnderstandVideoRequest;
import ai.core.api.server.hubmedia.HubMediaUnderstandVideoResponse;
import ai.core.cli.hub.HubCredentialResolver;
import ai.core.cli.hub.agent.LocalAttachmentUploader;
import ai.core.cli.http.RemoteApiClient;
import ai.core.tool.tools.UnderstandVideoTool;
import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Local {@code understand_video}: a {@code video_<uuid>} reference is understood as the caller, a local file or
 * URL is uploaded first. The server owns the Gemini file cache and model routing, so the answer is the same one
 * the sandbox tool produces.
 *
 * @author stephen
 */
public class HubVideoUnderstandingService implements UnderstandVideoTool.VideoUnderstandingService {
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    private static final String CLIENT_HEADER = "X-Core-AI-Client";

    private final RemoteApiClient apiClient;
    private final LocalAttachmentUploader uploader;
    private final HttpClient downloadClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public HubVideoUnderstandingService(HubCredentialResolver.HubCredentials credentials) {
        this.apiClient = new RemoteApiClient(credentials.serverUrl(), credentials.apiKey(), REQUEST_TIMEOUT,
                Map.of(CLIENT_HEADER, "cli"), credentials.insecure());
        this.uploader = new LocalAttachmentUploader(credentials.serverUrl(), credentials.apiKey(), credentials.insecure());
    }

    @Override
    public UnderstandVideoTool.VideoUnderstandingResult understand(UnderstandVideoTool.AttachmentOwner owner,
                                                                   String attachmentReferenceId,
                                                                   String effectiveModel, String question) {
        var request = new HubMediaUnderstandVideoRequest();
        request.question = question;
        request.model = effectiveModel;
        request.sessionId = owner.sessionId();
        if (attachmentReferenceId != null && attachmentReferenceId.startsWith("video_")) {
            request.attachmentReferenceId = attachmentReferenceId;
        } else {
            var attachment = upload(attachmentReferenceId);
            request.container = attachment.container;
            request.blobName = attachment.blobName;
            request.contentType = attachment.contentType;
            request.fileName = attachment.fileName;
        }
        var body = apiClient.postRequired("/api/hub/media/understand-video", request);
        var response = JsonUtil.fromJson(HubMediaUnderstandVideoResponse.class, body);
        return new UnderstandVideoTool.VideoUnderstandingResult(response.answer, response.model, response.fileCache,
                number(response.promptTokens), number(response.completionTokens), number(response.totalTokens));
    }

    private AgentHubAttachment upload(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("attachment_reference_id is required");
        }
        var local = localFile(reference);
        if (local != null) return uploader.upload(local);
        var uri = URI.create(reference);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("attachment_reference_id must be a video_<uuid>, a local file path or an http(s) URL");
        }
        return uploader.upload(download(uri), fileName(uri), "video/mp4");
    }

    private Path localFile(String reference) {
        try {
            var candidate = reference.startsWith("file:") ? Path.of(URI.create(reference)) : Path.of(reference);
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private byte[] download(URI uri) {
        try {
            var request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
            var response = downloadClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("failed to download video: HTTP " + response.statusCode());
            }
            if (response.body() == null || response.body().length == 0) {
                throw new IllegalStateException("failed to download video: empty body");
            }
            if (response.body().length > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException("video is too large: " + response.body().length + " bytes");
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("failed to download video: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("video download interrupted", e);
        }
    }

    private String fileName(URI uri) {
        var path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) return "video.mp4";
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }
}
