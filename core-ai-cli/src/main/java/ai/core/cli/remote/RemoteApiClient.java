package ai.core.cli.remote;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * @author stephen
 */
public class RemoteApiClient {
    private static final Logger logger = LoggerFactory.getLogger(RemoteApiClient.class);

    private final String serverUrl;
    private final String apiKey;
    private final HttpClient sseClient;
    private final HttpClient apiClient;

    public RemoteApiClient(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.sseClient = HttpClient.newBuilder().build();
        this.apiClient = HttpClient.newBuilder().build();
    }

    public HttpClient httpClient() {
        return sseClient;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String get(String path) {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        return send(request);
    }

    public String post(String path, Object body) {
        var json = body != null ? JsonUtil.toJson(body) : "{}";
        var request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return send(request);
    }

    public String put(String path, Object body) {
        var json = body != null ? JsonUtil.toJson(body) : "{}";
        var request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return send(request);
    }

    public String postEmpty(String path) {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    public void delete(String path) {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .DELETE()
                .build();
        send(request);
    }

    public HttpRequest.Builder putRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .header("Authorization", "Bearer " + apiKey);
    }

    private String send(HttpRequest request) {
        try {
            var response = apiClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                logger.warn("API error: {} {}", response.statusCode(), response.body());
                var message = parseErrorMessage(response.statusCode(), response.body());
                throw new RemoteApiException(response.statusCode(), message);
            }
            return response.body();
        } catch (RemoteApiException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("API request failed: {}", e.getMessage());
            return null;
        }
    }

    private String parseErrorMessage(int statusCode, String body) {
        if (body != null && !body.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> error = JsonUtil.fromJson(Map.class, body);
                var message = error.get("message");
                if (message != null) return String.valueOf(message);
            } catch (Exception ignored) {
                // failed to parse error body as JSON, fall through to generic message
            }
        }
        return switch (statusCode) {
            case 401 -> "authentication failed, please re-login with /remote";
            case 403 -> "access denied";
            case 404 -> "resource not found";
            default -> "server error (" + statusCode + ")";
        };
    }
}
