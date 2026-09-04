package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillHubDetail;
import ai.core.api.server.skillhub.SkillHubLookupResponse;
import ai.core.api.server.skillhub.SkillHubResourceResponse;
import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.cli.http.RemoteApiClient;
import ai.core.utils.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Thin typed client over the Skill Hub REST surface. The {@code X-Core-AI-Client: cli}
 * header lets the server attribute reads; pushing goes through the existing management
 * upload endpoint ({@code POST /api/skills/upload}, {@code skill.manage}).
 *
 * @author stephen
 */
public class SkillHubClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration ARCHIVE_TIMEOUT = Duration.ofSeconds(120);

    private final String serverUrl;
    private final String apiKey;
    private final Duration timeout;
    private final Duration archiveTimeout;

    public SkillHubClient(String serverUrl, String apiKey) {
        this(serverUrl, apiKey, TIMEOUT, ARCHIVE_TIMEOUT);
    }

    public SkillHubClient(String serverUrl, String apiKey, Duration timeout, Duration archiveTimeout) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.archiveTimeout = archiveTimeout;
    }

    public String serverUrl() {
        return serverUrl;
    }

    public SkillHubSearchResponse search(String query, String namespace, String sourceType, Integer limit) {
        var path = new StringBuilder(128).append("/api/hub/skills");
        char separator = '?';
        if (query != null && !query.isBlank()) {
            path.append(separator).append("query=").append(encode(query));
            separator = '&';
        }
        if (namespace != null && !namespace.isBlank()) {
            path.append(separator).append("namespace=").append(encode(namespace));
            separator = '&';
        }
        if (sourceType != null && !sourceType.isBlank()) {
            path.append(separator).append("source_type=").append(encode(sourceType));
            separator = '&';
        }
        if (limit != null) {
            path.append(separator).append("limit=").append(limit);
        }
        return get(path.toString(), SkillHubSearchResponse.class);
    }

    public SkillHubLookupResponse lookup(String name) {
        return get("/api/hub/skills/lookup?name=" + encode(name), SkillHubLookupResponse.class);
    }

    public SkillHubDetail show(String namespace, String name) {
        return get("/api/hub/skills/" + encode(namespace) + "/" + encode(name), SkillHubDetail.class);
    }

    public SkillHubResourceResponse resource(String namespace, String name, String path) {
        return get("/api/hub/skills/" + encode(namespace) + "/" + encode(name) + "/resource?path=" + encode(path),
                SkillHubResourceResponse.class);
    }

    public ArchiveResult archive(String namespace, String name) {
        var response = apiClient(archiveTimeout).getBytes("/api/hub/skills/" + encode(namespace) + "/" + encode(name) + "/archive");
        return new ArchiveResult(response.body(), header(response, "x-skill-id"), header(response, "x-skill-digest"),
                header(response, "x-skill-qualified-name"));
    }

    public String push(Map<String, Path> files) {
        return apiClient(timeout).postMultipart("/api/skills/upload", files);
    }

    private <T> T get(String path, Class<T> responseClass) {
        var body = apiClient(timeout).getRequired(path);
        return JsonUtil.fromJson(responseClass, body);
    }

    private RemoteApiClient apiClient(Duration timeout) {
        return new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"));
    }

    private String header(RemoteApiClient.BinaryResponse response, String name) {
        var value = response.headers().get(name);
        return value == null || value.isBlank() ? null : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ArchiveResult(byte[] bytes, String id, String digest, String qualifiedName) {
    }
}
