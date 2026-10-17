package ai.core.cli.hub.catalog;

import ai.core.api.server.hubcatalog.HubCatalogResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.Map;

/**
 * Client of {@code GET /api/hub/catalog}: every MCP tool, Service API operation and agent /
 * LLM_CALL definition the account can call, in one request. A full catalog is a large response the
 * server assembles on demand, so the timeout is more generous than a single search's.
 *
 * @author stephen
 */
public class CatalogHubClient {
    private static final String CLIENT_HEADER = "x-core-ai-client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;
    private final Duration timeout;

    public CatalogHubClient(String serverUrl, String apiKey, boolean insecure) {
        this(serverUrl, apiKey, insecure, DEFAULT_TIMEOUT);
    }

    /** Interactive callers (the desktop panel) pass a shorter budget than the batch CLI default. */
    public CatalogHubClient(String serverUrl, String apiKey, boolean insecure, Duration timeout) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
        this.timeout = timeout;
    }

    public HubCatalogResponse catalog() {
        var body = new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"), insecure)
                .getRequired("/api/hub/catalog");
        return JsonUtil.fromJson(HubCatalogResponse.class, body);
    }
}
