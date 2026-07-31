package ai.core.server.domain;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public final class GeminiFilesClient {
    private static String stripTrailingSlash(String value) {
        var result = value == null || value.isBlank() ? "https://generativelanguage.googleapis.com" : value;
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private final String baseUrl;
    private final String apiKey;
    private final String gcsBucket;
    private final String bearerToken;
    private final HttpClient client;

    /**
     * Gemini Developer API backend, authenticates with an API key.
     */
    public GeminiFilesClient(String baseUrl, String apiKey) {
        this(stripTrailingSlash(baseUrl), apiKey, null, null);
    }

    /**
     * Vertex AI backend, uploads videos to Cloud Storage and authenticates with a service account bearer token.
     */
    public GeminiFilesClient(String gcsBucket, String bearerToken, boolean vertex) {
        this(null, null, gcsBucket, bearerToken);
    }

    private GeminiFilesClient(String baseUrl, String apiKey, String gcsBucket, String bearerToken) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.gcsBucket = gcsBucket;
        this.bearerToken = bearerToken;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public boolean vertex() {
        return gcsBucket != null;
    }

    public UploadedFile upload(Path file, String contentType, String displayName) {
        if (vertex()) return uploadToGcs(file, contentType, displayName);
        return uploadResumable(file, contentType, displayName);
    }

    public FileState get(String name) {
        if (vertex()) throw new UnsupportedOperationException("Vertex file state is not tracked by Gemini Files API");
        try {
            return parseState(send(request("GET", baseUrl + "/v1beta/" + name, null).GET().build()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini Files get failed", e);
        } catch (IOException e) {
            throw new RuntimeException("Gemini Files get failed", e);
        }
    }

    public void delete(String name) {
        if (vertex()) throw new UnsupportedOperationException("Vertex files are not deleted through Gemini Files API");
        try {
            send(request("DELETE", baseUrl + "/v1beta/" + name, null).DELETE().build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini Files delete failed", e);
        } catch (IOException e) {
            throw new RuntimeException("Gemini Files delete failed", e);
        }
    }

    private UploadedFile uploadResumable(Path file, String contentType, String displayName) {
        try {
            var size = Files.size(file);
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("file", Map.of("displayName", displayName));
            var start = request("POST", baseUrl + "/upload/v1beta/files", JsonUtil.toJson(metadata))
                    .header("X-Goog-Upload-Protocol", "resumable")
                    .header("X-Goog-Upload-Command", "start")
                    .header("X-Goog-Upload-Header-Content-Length", String.valueOf(size))
                    .header("X-Goog-Upload-Header-Content-Type", contentType)
                    .build();
            var startResponse = send(start);
            var uploadUrl = startResponse.headers().firstValue("X-Goog-Upload-URL")
                    .orElseThrow(() -> new IllegalStateException("Gemini upload URL missing"));
            var upload = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .timeout(Duration.ofMinutes(30))
                    .header("X-Goog-Upload-Offset", "0")
                    .header("X-Goog-Upload-Command", "upload, finalize")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            return parseUploaded(send(upload));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini Files upload failed", e);
        } catch (IOException e) {
            throw new RuntimeException("Gemini Files upload failed", e);
        }
    }

    private UploadedFile uploadToGcs(Path file, String contentType, String displayName) {
        try {
            var objectName = "gemini-video/" + UUID.randomUUID() + "-" + safeName(displayName);
            var encodedName = URLEncoder.encode(objectName, StandardCharsets.UTF_8).replace("+", "%20");
            var uploadUrl = "https://storage.googleapis.com/upload/storage/v1/b/" + gcsBucket
                    + "/o?uploadType=media&name=" + encodedName;
            var upload = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .timeout(Duration.ofMinutes(30))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            send(upload);
            return new UploadedFile(objectName, "gs://" + gcsBucket + "/" + objectName, null, "ACTIVE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("GCS video upload failed", e);
        } catch (IOException e) {
            throw new RuntimeException("GCS video upload failed", e);
        }
    }

    private String safeName(String displayName) {
        if (displayName == null || displayName.isBlank()) return "video.bin";
        return displayName.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("_+", "_");
    }

    private HttpRequest.Builder request(String method, String url, String body) {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Gemini Files API failed: HTTP " + response.statusCode() + ": "
                    + truncate(response.body(), 300));
        }
        return response;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    @SuppressWarnings("unchecked")
    private UploadedFile parseUploaded(HttpResponse<String> response) {
        var root = (Map<String, Object>) JsonUtil.fromJson(Map.class, response.body());
        var file = (Map<String, Object>) root.get("file");
        if (file == null) file = root;
        return new UploadedFile((String) file.get("name"), (String) file.get("uri"),
                (String) file.get("expirationTime"), (String) file.get("state"));
    }

    @SuppressWarnings("unchecked")
    private FileState parseState(HttpResponse<String> response) {
        var file = (Map<String, Object>) JsonUtil.fromJson(Map.class, response.body());
        return new FileState((String) file.get("name"), (String) file.get("uri"),
                (String) file.get("expirationTime"), (String) file.get("state"));
    }

    public record UploadedFile(String name, String uri, String expirationTime, String state) { }
    public record FileState(String name, String uri, String expirationTime, String state) { }
}
