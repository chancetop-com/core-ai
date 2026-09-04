package ai.core.cli.http;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class RemoteApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteApiClient.class);

    private final String serverUrl;
    private final String apiKey;
    private final Duration requestTimeout;
    private final Map<String, String> defaultHeaders;
    private final HttpClient sseClient;
    private final HttpClient apiClient;

    public RemoteApiClient(String serverUrl, String apiKey) {
        this(serverUrl, apiKey, null, Map.of());
    }

    public RemoteApiClient(String serverUrl, String apiKey, Duration requestTimeout) {
        this(serverUrl, apiKey, requestTimeout, Map.of());
    }

    public RemoteApiClient(String serverUrl, String apiKey, Duration requestTimeout, Map<String, String> defaultHeaders) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.defaultHeaders = defaultHeaders;
        this.sseClient = createHttpClient(requestTimeout);
        this.apiClient = createHttpClient(requestTimeout);
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
        var request = request(path)
                .GET()
                .build();
        return send(request);
    }

    public String getRequired(String path) {
        var request = request(path)
                .GET()
                .build();
        return sendRequired(request);
    }

    /** Binary GET: returns body bytes plus response headers (e.g. digest headers of an archive download). */
    public BinaryResponse getBytes(String path) {
        var request = request(path)
                .GET()
                .build();
        try {
            var response = apiClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                var message = parseErrorMessage(response.statusCode(), response.body() == null ? null
                        : new String(response.body(), StandardCharsets.UTF_8));
                LOGGER.warn("API error: {} {}", response.statusCode(), message);
                throw new RemoteApiException(response.statusCode(), message);
            }
            var headers = new java.util.HashMap<String, String>();
            response.headers().map().forEach((name, values) ->
                    headers.put(name, values == null || values.isEmpty() ? null : values.getFirst()));
            byte[] body = response.body() == null ? new byte[0] : response.body();
            return new BinaryResponse(body, headers);
        } catch (RemoteApiException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("API request failed: {}", e.getMessage());
            throw new IllegalStateException("API request failed: " + e.getMessage(), e);
        }
    }

    public String post(String path, Object body) {
        var json = body != null ? JsonUtil.toJson(body) : "{}";
        var request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return send(request);
    }

    public String postRequired(String path, Object body) {
        var json = body != null ? JsonUtil.toJson(body) : "{}";
        var request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return sendRequired(request);
    }

    public String put(String path, Object body) {
        var json = body != null ? JsonUtil.toJson(body) : "{}";
        var request = request(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return send(request);
    }

    public String postEmpty(String path) {
        var request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    public void delete(String path) {
        var request = request(path)
                .DELETE()
                .build();
        send(request);
    }

    public String postMultipart(String path, Map<String, Path> files) {
        var boundary = UUID.randomUUID().toString();
        try {
            var parts = new ArrayList<byte[]>();
            for (var entry : files.entrySet()) {
                var fieldName = entry.getKey();
                var file = entry.getValue();
                Path fileNamePath = file.getFileName();
                if (fileNamePath == null) continue;
                var fileName = fileNamePath.toString();
                var header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
                parts.add(header.getBytes(StandardCharsets.UTF_8));
                parts.add(Files.readAllBytes(file));
                parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            int totalLen = parts.stream().mapToInt(b -> b.length).sum();
            var body = new byte[totalLen];
            int offset = 0;
            for (var part : parts) {
                System.arraycopy(part, 0, body, offset, part.length);
                offset += part.length;
            }

            var request = request(path)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            return send(request);
        } catch (IOException e) {
            LOGGER.warn("failed to read files for multipart upload", e);
            return null;
        }
    }

    public HttpRequest.Builder putRequest(String path) {
        return request(path);
    }

    private HttpClient createHttpClient(Duration timeout) {
        var builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);
        if (timeout != null) builder.connectTimeout(timeout);
        return builder.build();
    }

    private HttpRequest.Builder request(String path) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .header("Authorization", "Bearer " + apiKey);
        for (var header : defaultHeaders.entrySet()) {
            if (header.getValue() != null) builder.header(header.getKey(), header.getValue());
        }
        if (requestTimeout != null) builder.timeout(requestTimeout);
        return builder;
    }

    private String send(HttpRequest request) {
        try {
            var response = apiClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOGGER.warn("API error: {} {}", response.statusCode(), response.body());
                var message = parseErrorMessage(response.statusCode(), response.body());
                throw new RemoteApiException(response.statusCode(), message);
            }
            return response.body();
        } catch (RemoteApiException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("API request failed: {}", e.getMessage());
            return null;
        }
    }

    private String sendRequired(HttpRequest request) {
        try {
            var response = apiClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOGGER.warn("API error: {} {}", response.statusCode(), response.body());
                var message = parseErrorMessage(response.statusCode(), response.body());
                throw new RemoteApiException(response.statusCode(), message);
            }
            return response.body();
        } catch (RemoteApiException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("API request failed: {}", e.getMessage());
            throw new IllegalStateException("API request failed: " + e.getMessage(), e);
        }
    }

    private String parseErrorMessage(int statusCode, String body) {
        if (body == null || body.isBlank()) return genericMessage(statusCode);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> error = JsonUtil.fromJson(Map.class, body);
            String message = topLevelMessage(error);
            if (message != null) return message;
        } catch (RuntimeException ignored) {
            // failed to parse error body as JSON, fall through to generic message
        }
        return genericMessage(statusCode);
    }

    @SuppressWarnings("unchecked")
    private String topLevelMessage(Map<String, Object> error) {
        var message = error.get("message");
        if (message != null) return String.valueOf(message);
        var nested = error.get("error");
        if (nested instanceof Map<?, ?> errorBody) {
            var nestedMessage = errorBody.get("message");
            if (nestedMessage != null) return String.valueOf(nestedMessage);
        }
        return null;
    }

    private String genericMessage(int statusCode) {
        return switch (statusCode) {
            case 401 -> "authentication failed, please run 'core-ai-cli --login' to log in";
            case 403 -> "access denied";
            case 404 -> "resource not found";
            default -> "server error (" + statusCode + ")";
        };
    }

    public record BinaryResponse(byte[] body, Map<String, String> headers) {
    }
}
