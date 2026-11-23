package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetDataRequest;
import ai.core.api.server.hub.HubDatasetListView;
import ai.core.api.server.hub.HubDatasetOpResponse;
import ai.core.api.server.hub.HubDatasetRenameRequest;
import ai.core.api.server.hub.HubDatasetView;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Dataset access over the hub endpoints, the local counterpart of the sandbox hub call path. A session narrows every
 * operation to {@code /api/hub/sessions/:sessionId/datasets/*} and the agent's bindings; without one, the catalog and
 * GENERAL-dataset operations use {@code /api/hub/datasets/*}. Both answer with the payload text the agent tools
 * produce, so a script reads the same thing wherever it runs; this client only moves that text.
 *
 * @author stephen
 */
public class DatasetHubClient {
    private static final String CLIENT_HEADER = "x-core-ai-client";
    private static final String DATASET_BASE = "/api/hub/datasets";
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

    /** The dataset catalog, without a session: every dataset, filtered and paged by the server. */
    public HubDatasetListView datasets(String query, Integer offset, Integer limit) {
        var path = withParams(DATASET_BASE, List.of(new Param("q", query),
                new Param("offset", offset == null ? null : String.valueOf(offset)),
                new Param("limit", limit == null ? null : String.valueOf(limit))));
        var body = api().getRequired(path);
        return JsonUtil.fromJson(HubDatasetListView.class, body);
    }

    /** One dataset's metadata, without a session; the ref is a dataset id or a name unique in the catalog. */
    public HubDatasetView dataset(String datasetRef) {
        var body = api().getRequired(DATASET_BASE + "/" + encode(datasetRef));
        return JsonUtil.fromJson(HubDatasetView.class, body);
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
        return read(withParams(base(sessionId) + "/" + encode(datasetRef) + "/records", query.params()));
    }

    public HubDatasetOpResponse queryDatasetRecords(String datasetRef, RecordQuery query) {
        return read(withParams(DATASET_BASE + "/" + encode(datasetRef) + "/records", query.params()));
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

    public HubDatasetOpResponse insertDatasetRecord(String datasetRef, String dataText) {
        return write("POST", DATASET_BASE + "/" + encode(datasetRef) + "/records", dataText);
    }

    public HubDatasetOpResponse updateDatasetRecord(String datasetRef, String recordId, String dataText) {
        return write("POST", DATASET_BASE + "/" + encode(datasetRef) + "/records/" + encode(recordId), dataText);
    }

    public HubDatasetOpResponse deleteDatasetRecord(String datasetRef, String recordId) {
        return JsonUtil.fromJson(HubDatasetOpResponse.class,
                api().deleteRequired(DATASET_BASE + "/" + encode(datasetRef) + "/records/" + encode(recordId)));
    }

    public HubDatasetOpResponse renameField(String sessionId, String datasetRef, String from, String to) {
        return rename(base(sessionId) + "/" + encode(datasetRef) + "/fields/rename", from, to);
    }

    public HubDatasetOpResponse renameDatasetField(String datasetRef, String from, String to) {
        return rename(DATASET_BASE + "/" + encode(datasetRef) + "/fields/rename", from, to);
    }

    private HubDatasetOpResponse rename(String path, String from, String to) {
        var request = new HubDatasetRenameRequest();
        request.from = from;
        request.to = to;
        return JsonUtil.fromJson(HubDatasetOpResponse.class, api().postRequired(path, request));
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

    private String withParams(String path, List<Param> params) {
        var builder = new StringBuilder(path);
        char separator = '?';
        for (var param : params) {
            if (param.value() == null || param.value().isBlank()) continue;
            builder.append(separator).append(param.name()).append('=').append(encode(param.value()));
            separator = '&';
        }
        return builder.toString();
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
