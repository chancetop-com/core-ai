package ai.core.cli.hub;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubServersResponse;
import ai.core.api.server.mcphub.HubToolDetail;
import ai.core.api.server.mcphub.HubToolsResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Thin typed client over the MCP Hub REST surface. Adds the {@code X-Core-AI-Client: cli}
 * header so server-side audit records can attribute the caller.
 *
 * @author stephen
 */
public class HubClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CALL_GRACE = Duration.ofSeconds(30);

    private final String serverUrl;
    private final String apiKey;

    public HubClient(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
    }

    public HubServersResponse servers() {
        return get("/api/mcp-hub/servers", HubServersResponse.class);
    }

    public HubToolsResponse search(String query, String server, Integer limit) {
        var path = new StringBuilder(128).append("/api/mcp-hub/tools");
        char separator = '?';
        if (query != null && !query.isBlank()) {
            path.append(separator).append("query=").append(encode(query));
            separator = '&';
        }
        if (server != null && !server.isBlank()) {
            path.append(separator).append("server=").append(encode(server));
            separator = '&';
        }
        if (limit != null) {
            path.append(separator).append("limit=").append(limit);
        }
        return get(path.toString(), HubToolsResponse.class);
    }

    public HubToolDetail describe(String server, String tool) {
        return get("/api/mcp-hub/tools/" + encode(server) + "/" + encode(tool), HubToolDetail.class);
    }

    public HubCallResponse call(String server, String tool, String argumentsJson, Integer timeoutSeconds) {
        var request = new HubCallRequest();
        request.arguments = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        request.timeoutSeconds = timeoutSeconds;
        var timeout = timeoutSeconds == null
                ? DEFAULT_TIMEOUT : Duration.ofSeconds(Math.min(timeoutSeconds, 300) + CALL_GRACE.toSeconds());
        var api = apiClient(timeout);
        var body = api.postRequired("/api/mcp-hub/tools/" + encode(server) + "/" + encode(tool) + "/call", request);
        return JsonUtil.fromJson(HubCallResponse.class, body);
    }

    private <T> T get(String path, Class<T> responseClass) {
        var body = apiClient(DEFAULT_TIMEOUT).getRequired(path);
        return JsonUtil.fromJson(responseClass, body);
    }

    private RemoteApiClient apiClient(Duration timeout) {
        return new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
