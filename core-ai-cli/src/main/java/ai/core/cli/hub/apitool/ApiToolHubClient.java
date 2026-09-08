package ai.core.cli.hub.apitool;

import ai.core.api.server.apitoolhub.ApiToolHubAppsResponse;
import ai.core.api.server.apitoolhub.ApiToolHubLookupResponse;
import ai.core.api.server.apitoolhub.ApiToolHubOperationDetail;
import ai.core.api.server.apitoolhub.ApiToolHubSearchResponse;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Thin typed client over the API-Tool Hub REST surface. Adds the
 * {@code X-Core-AI-Client: cli} header so server-side audit records can attribute
 * the caller.
 *
 * @author stephen
 */
public class ApiToolHubClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CALL_GRACE = Duration.ofSeconds(30);

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;

    public ApiToolHubClient(String serverUrl, String apiKey, boolean insecure) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
    }

    public ApiToolHubAppsResponse apps() {
        return get("/api/hub/api-tools/apps", ApiToolHubAppsResponse.class);
    }

    public ApiToolHubSearchResponse search(String query, String app, String service, Integer limit) {
        var path = new StringBuilder(128).append("/api/hub/api-tools");
        char separator = '?';
        if (query != null && !query.isBlank()) {
            path.append(separator).append("query=").append(encode(query));
            separator = '&';
        }
        if (app != null && !app.isBlank()) {
            path.append(separator).append("app=").append(encode(app));
            separator = '&';
        }
        if (service != null && !service.isBlank()) {
            path.append(separator).append("service=").append(encode(service));
            separator = '&';
        }
        if (limit != null) {
            path.append(separator).append("limit=").append(limit);
        }
        return get(path.toString(), ApiToolHubSearchResponse.class);
    }

    public ApiToolHubOperationDetail describe(String app, String service, String operation) {
        return get("/api/hub/api-tools/" + encode(app) + "/" + encode(service) + "/" + encode(operation),
                ApiToolHubOperationDetail.class);
    }

    public ApiToolHubLookupResponse lookup(String toolName) {
        return get("/api/hub/api-tools/lookup?tool_name=" + encode(toolName), ApiToolHubLookupResponse.class);
    }

    public HubCallResponse call(String app, String service, String operation, String argumentsJson, Integer timeoutSeconds) {
        var request = new HubCallRequest();
        request.arguments = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        request.timeoutSeconds = timeoutSeconds;
        var timeout = timeoutSeconds == null
                ? DEFAULT_TIMEOUT : Duration.ofSeconds(Math.min(timeoutSeconds, 300) + CALL_GRACE.toSeconds());
        var body = apiClient(timeout).postRequired(
                "/api/hub/api-tools/" + encode(app) + "/" + encode(service) + "/" + encode(operation) + "/call", request);
        return JsonUtil.fromJson(HubCallResponse.class, body);
    }

    private <T> T get(String path, Class<T> responseClass) {
        var body = apiClient(DEFAULT_TIMEOUT).getRequired(path);
        return JsonUtil.fromJson(responseClass, body);
    }

    private RemoteApiClient apiClient(Duration timeout) {
        return new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"), insecure);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
