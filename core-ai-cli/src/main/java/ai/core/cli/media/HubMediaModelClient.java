package ai.core.cli.media;

import ai.core.api.server.hubmodels.ListHubMediaModelsResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Thin typed client over the media model catalog ({@code /api/hub/media-models}).
 *
 * @author stephen
 */
public class HubMediaModelClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;

    public HubMediaModelClient(String serverUrl, String apiKey, boolean insecure) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
    }

    public ListHubMediaModelsResponse models() {
        return JsonUtil.fromJson(ListHubMediaModelsResponse.class,
                new RemoteApiClient(serverUrl, apiKey, DEFAULT_TIMEOUT, Map.of(CLIENT_HEADER, "cli"), insecure)
                        .getRequired("/api/hub/media-models"));
    }
}
