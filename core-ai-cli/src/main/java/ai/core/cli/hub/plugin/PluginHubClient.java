package ai.core.cli.hub.plugin;

import ai.core.api.server.plugin.PluginRegistryEntryView;
import ai.core.api.server.plugin.PluginRegistrySearchResponse;
import ai.core.api.server.plugin.RegisterPluginRequest;
import ai.core.cli.http.RemoteApiClient;
import ai.core.cli.http.RemoteApiException;
import ai.core.cli.http.TlsTrust;
import ai.core.utils.JsonUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Client of the desktop plugin registry ({@code /api/plugins}). Reads are open to any signed-in
 * user with {@code plugin.view}; publishing and deleting need {@code plugin.manage}. The registry
 * never serves package bytes — {@link #download} fetches the pinned URL and verifies the digest.
 *
 * @author stephen
 */
public class PluginHubClient {
    private static final String CLIENT_HEADER = "X-Core-AI-Client";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final long MAX_PACKAGE_BYTES = 256L * 1024 * 1024;

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private final String serverUrl;
    private final String apiKey;
    private final boolean insecure;

    public PluginHubClient(String serverUrl, String apiKey, boolean insecure) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
        this.insecure = insecure;
    }

    public PluginRegistrySearchResponse search(String query, Integer offset, Integer limit) {
        var path = new StringBuilder(128).append("/api/plugins");
        char separator = '?';
        if (query != null && !query.isBlank()) {
            path.append(separator).append("query=").append(encode(query));
            separator = '&';
        }
        if (offset != null) path.append(separator).append("offset=").append(offset);
        if (limit != null) path.append(separator).append("limit=").append(limit);
        return fetch(path.toString(), PluginRegistrySearchResponse.class);
    }

    public PluginRegistryEntryView get(String id) {
        return fetch("/api/plugins/" + encode(id), PluginRegistryEntryView.class);
    }

    public PluginRegistryEntryView register(RegisterPluginRequest request) {
        var body = apiClient(TIMEOUT).postRequired("/api/plugins", request);
        return JsonUtil.fromJson(PluginRegistryEntryView.class, body);
    }

    public void delete(String id) {
        apiClient(TIMEOUT).delete("/api/plugins/" + encode(id));
    }

    /** Downloads the package pinned by the registry and verifies its sha256 and size. */
    public byte[] download(PluginRegistryEntryView plugin) {
        validateDownload(plugin);
        URI target = URI.create(plugin.downloadUrl);
        var bytes = fetchBytes(target, sameOrigin(target));
        if (bytes.length > MAX_PACKAGE_BYTES) {
            throw new IllegalStateException("plugin package exceeds 256 MB");
        }
        if (plugin.size != null && bytes.length != plugin.size) {
            throw new IllegalStateException("plugin package size does not match the registry ("
                    + bytes.length + " != " + plugin.size + ")");
        }
        if (!sha256(bytes).equalsIgnoreCase(plugin.sha256)) {
            throw new IllegalStateException("plugin package digest does not match the registry");
        }
        return bytes;
    }

    public String serverUrl() {
        return serverUrl;
    }

    private void validateDownload(PluginRegistryEntryView plugin) {
        if (plugin.downloadUrl == null || plugin.downloadUrl.isBlank()) {
            throw new IllegalStateException("plugin has no download URL");
        }
        if (plugin.sha256 == null || plugin.sha256.length() != 64) {
            throw new IllegalStateException("plugin has an invalid sha256");
        }
        if (plugin.size != null && (plugin.size <= 0 || plugin.size > MAX_PACKAGE_BYTES)) {
            throw new IllegalStateException("plugin has an invalid size");
        }
        var scheme = URI.create(plugin.downloadUrl).getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("plugin download URL must be http(s)");
        }
    }

    private byte[] fetchBytes(URI target, boolean sameOrigin) {
        var builder = HttpRequest.newBuilder(target)
                .GET()
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream");
        if (sameOrigin) builder.header("Authorization", "Bearer " + apiKey);
        try {
            var clientBuilder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30));
            if (insecure && sameOrigin) clientBuilder.sslContext(TlsTrust.trustAllContext());
            var response = clientBuilder.build().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RemoteApiException(response.statusCode(),
                        "plugin download failed (HTTP " + response.statusCode() + ")");
            }
            return response.body() == null ? new byte[0] : response.body();
        } catch (RemoteApiException | IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("plugin download interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("plugin download failed: " + e.getMessage(), e);
        }
    }

    private boolean sameOrigin(URI target) {
        try {
            var server = URI.create(serverUrl);
            if (server.getHost() == null) return false;
            if (target.getHost() == null) return false;
            if (!equalsIgnoreCase(target.getScheme(), server.getScheme())) return false;
            if (!equalsIgnoreCase(target.getHost(), server.getHost())) return false;
            return portOf(target) == portOf(server);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int portOf(URI uri) {
        return uri.getPort() == -1 ? ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443) : uri.getPort();
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
    }

    private <T> T fetch(String path, Class<T> responseClass) {
        var body = apiClient(TIMEOUT).getRequired(path);
        return JsonUtil.fromJson(responseClass, body);
    }

    private RemoteApiClient apiClient(Duration timeout) {
        return new RemoteApiClient(serverUrl, apiKey, timeout, Map.of(CLIENT_HEADER, "cli"), insecure);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
