package ai.core.cli.hub.model;

import ai.core.api.server.hubmodels.ListHubModelsResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Thin typed client over the model hub surface ({@code /api/hub/models}) — the chat models the
 * caller may use, with the reasoning-effort levels each model declares.
 *
 * @author stephen
 */
public class ModelHubClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;

    public ModelHubClient(String serverUrl, String apiKey, boolean insecure) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
    }

    public ListHubModelsResponse models() {
        return JsonUtil.fromJson(ListHubModelsResponse.class,
                new RemoteApiClient(serverUrl, apiKey, DEFAULT_TIMEOUT, Map.of(CLIENT_HEADER, "cli"), insecure)
                        .getRequired("/api/hub/models"));
    }
}
