package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetDataRequest;
import ai.core.api.server.hub.HubDatasetListView;
import ai.core.api.server.hub.HubDatasetOpResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Session-anchored dataset access over {@code /api/hub/sessions/:sessionId/datasets/*}, the local counterpart of the
 * sandbox hub call path. Every operation answers with the payload text the agent tools produce, so a script reads the
 * same thing wherever it runs; this client only moves that text.
 *
 * @author stephen
 */
public class DatasetHubClient {
    private static final String CLIENT_HEADER = "x-core-ai-client";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;
    private final Duration timeout;

    public DatasetHubClient(String serverUrl, String apiKey, boolean insecure, Duration timeout) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public HubDatasetListView datasets(String sessionId) {
        var body = api().getRequired(base(sessionId));
        return JsonUtil.fromJson(HubDatasetListView.class, body);
    }

    public HubDatasetOpResponse getState(String sessionId, String datasetRef, String fields) {
        return read(base(sessionId) + "/" + encode(datasetRef) + "/state" + query("fields", fields));
    }

    public HubDatasetOpResponse saveState(String sessionId, String datasetRef, String dataText) {
        return write("PUT", base(sessionId) + "/" + encode(datasetRef) + "/state", dataText);
    }

    public HubDatasetOpResponse patchState(String sessionId, String datasetRef, String dataText) {
        return write("POST", base(sessionId) + "/" + encode(datasetRef) + "/state/patch", dataText);
    }

    public HubDatasetOpResponse queryRecords(String sessionId, String datasetRef, RecordQuery query) {
        var path = new StringBuilder(base(sessionId)).append('/').append(encode(datasetRef)).append("/records");
        char separator = '?';
        for (var param : query.params()) {
            if (param.value() == null || param.value().isBlank()) continue;
            path.append(separator).append(param.name()).append('=').append(encode(param.value()));
            separator = '&';
        }
        return read(path.toString());
    }

    public HubDatasetOpResponse insertRecord(String sessionId, String datasetRef, String dataText) {
        return write("POST", base(sessionId) + "/" + encode(datasetRef) + "/records", dataText);
    }

    public HubDatasetOpResponse updateRecord(String sessionId, String datasetRef, String recordId, String dataText) {
        return write("POST", base(sessionId) + "/" + encode(datasetRef) + "/records/" + encode(recordId), dataText);
    }

    public HubDatasetOpResponse deleteRecord(String sessionId, String datasetRef, String recordId) {
        var path = base(sessionId) + "/" + encode(datasetRef) + "/records/" + encode(recordId);
        return JsonUtil.fromJson(HubDatasetOpResponse.class, api().deleteRequired(path));
    }

    private HubDatasetOpResponse read(String path) {
        return JsonUtil.fromJson(HubDatasetOpResponse.class, api().getRequired(path));
    }

    private HubDatasetOpResponse write(String method, String path, String dataText) {
        var request = new HubDatasetDataRequest();
        request.data = dataText;
        var body = "PUT".equals(method) ? api().putRequired(path, request) : api().postRequired(path, request);
        return JsonUtil.fromJson(HubDatasetOpResponse.class, body);
    }

    private String base(String sessionId) {
        return "/api/hub/sessions/" + encode(sessionId) + "/datasets";
    }

    private String query(String name, String value) {
        return value == null || value.isBlank() ? "" : "?" + name + "=" + encode(value);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RemoteApiClient api() {
        return new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"), insecure);
    }

    /** Query parameters of {@code records.query}, all optional and omitted when unset. */
    public record RecordQuery(String filter, String fields, String from, String to, Integer limit, Integer offset) {
        public List<Param> params() {
            return List.of(new Param("filter", filter), new Param("fields", fields), new Param("from", from),
                    new Param("to", to), new Param("limit", limit == null ? null : String.valueOf(limit)),
                    new Param("offset", offset == null ? null : String.valueOf(offset)));
        }
    }

    public record Param(String name, String value) {
    }
}
