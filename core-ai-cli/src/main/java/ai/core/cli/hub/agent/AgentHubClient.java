package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.api.server.agenthub.AgentHubLookupResponse;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.api.server.agenthub.AgentHubSearchResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Thin typed client over the Agent Hub REST surface ({@code /api/hub/agents/*}).
 * <p>
 * {@code run}/{@code reply} block server-side until the task reaches a terminal state or the
 * request's own wait budget runs out, so the HTTP timeout is that budget plus a grace period —
 * a client timeout here would lose the task id the caller needs to poll.
 *
 * @author stephen
 */
public class AgentHubClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_WAIT_GRACE_SECONDS = 30;
    private static final int DEFAULT_WAIT_SECONDS = 120;
    private static final int MAX_WAIT_SECONDS = 300;

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;

    public AgentHubClient(String serverUrl, String apiKey, boolean insecure) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
    }

    public AgentHubSearchResponse search(String query, String type, String source, Integer limit) {
        var path = new StringBuilder(128).append("/api/hub/agents");
        char separator = '?';
        if (query != null && !query.isBlank()) {
            path.append(separator).append("query=").append(encode(query));
            separator = '&';
        }
        if (type != null && !type.isBlank()) {
            path.append(separator).append("type=").append(encode(type));
            separator = '&';
        }
        if (source != null && !source.isBlank()) {
            path.append(separator).append("source=").append(encode(source));
            separator = '&';
        }
        if (limit != null) {
            path.append(separator).append("limit=").append(limit);
        }
        return get(path.toString(), AgentHubSearchResponse.class);
    }

    public AgentHubLookupResponse lookup(String name) {
        return get("/api/hub/agents/lookup?name=" + encode(name), AgentHubLookupResponse.class);
    }

    public AgentHubDetail show(String id) {
        return get("/api/hub/agents/" + encode(id), AgentHubDetail.class);
    }

    public AgentHubRunResult run(String id, AgentHubRunRequest request) {
        return JsonUtil.fromJson(AgentHubRunResult.class,
                post("/api/hub/agents/" + encode(id) + "/run", request, waitTimeout(request.timeoutSeconds)));
    }

    public AgentHubRunResult status(String taskId) {
        return get("/api/hub/agents/runs/" + encode(taskId), AgentHubRunResult.class);
    }

    public AgentHubRunResult reply(String taskId, AgentHubReplyRequest request) {
        return JsonUtil.fromJson(AgentHubRunResult.class,
                post("/api/hub/agents/runs/" + encode(taskId) + "/reply", request,
                        Duration.ofSeconds(DEFAULT_WAIT_SECONDS + DEFAULT_WAIT_GRACE_SECONDS)));
    }

    public AgentHubRunResult cancel(String taskId) {
        return JsonUtil.fromJson(AgentHubRunResult.class,
                post("/api/hub/agents/runs/" + encode(taskId) + "/cancel", null, DEFAULT_TIMEOUT));
    }

    private <T> T get(String path, Class<T> responseClass) {
        return JsonUtil.fromJson(responseClass, apiClient(DEFAULT_TIMEOUT).getRequired(path));
    }

    private String post(String path, Object body, Duration timeout) {
        return apiClient(timeout).postRequired(path, body);
    }

    private Duration waitTimeout(Integer timeoutSeconds) {
        int wait = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_WAIT_SECONDS
                : Math.min(timeoutSeconds, MAX_WAIT_SECONDS);
        return Duration.ofSeconds(wait + DEFAULT_WAIT_GRACE_SECONDS);
    }

    private RemoteApiClient apiClient(Duration timeout) {
        return new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"), insecure);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
