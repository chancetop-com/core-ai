package ai.core.cli.remote;

import ai.core.cli.DebugLog;
import ai.core.utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * @author stephen
 */
public class RemoteApiClient {
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
                DebugLog.log("API error: " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (Exception e) {
            DebugLog.log("API request failed: " + e.getMessage());
            return null;
        }
    }
}
