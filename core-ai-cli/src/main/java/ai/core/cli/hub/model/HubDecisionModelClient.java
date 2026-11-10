package ai.core.cli.hub.model;

import ai.core.api.server.hubmodels.ListHubDecisionModelsResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Typed client over the decision-model catalog ({@code /api/hub/decision-models}).
 *
 * @author stephen
 */
public class HubDecisionModelClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;

    public HubDecisionModelClient(String serverUrl, String apiKey, boolean insecure) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
    }

    public ListHubDecisionModelsResponse models() {
        return JsonUtil.fromJson(ListHubDecisionModelsResponse.class,
                new RemoteApiClient(serverUrl, apiKey, DEFAULT_TIMEOUT, Map.of(CLIENT_HEADER, "cli"), insecure)
                        .getRequired("/api/hub/decision-models"));
    }
}
