package ai.core.cli.upgrade;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public final class UpgradeChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpgradeChecker.class);

    private static final String RELEASES_LATEST_API = "https://api.github.com/repos/chancetop-com/core-ai/releases/latest";
    private static final String RELEASES_API = "https://api.github.com/repos/chancetop-com/core-ai/releases?per_page=30";
    private static final Pattern CLI_TAG_PATTERN = Pattern.compile("^v\\d+(\\.\\d+)*$");

    /**
     * The repository carries more than one release stream (core-ai-cli shares it with the sandbox
     * runtime) and the repository-wide "latest" release is simply whichever stream published last,
     * so only a tag shaped like a CLI version counts as a CLI release.
     */
    private static String cliVersion(Map<String, Object> release) {
        Object tag = release.get("tag_name");
        if (!(tag instanceof String text) || !CLI_TAG_PATTERN.matcher(text).matches()) {
            return null;
        }
        if (Boolean.TRUE.equals(release.get("prerelease"))) {
            return null;
        }
        return text.substring(1);
    }

    private static Map<String, Object> parseRelease(String json) {
        if (json == null) return null;
        try {
            return JsonUtil.fromJson(new TypeReference<>() { }, json);
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to parse latest release: {}", e.getMessage());
            return null;
        }
    }

    private static List<Map<String, Object>> parseReleases(String json) {
        if (json == null) return List.of();
        try {
            return JsonUtil.fromJson(new TypeReference<>() { }, json);
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to parse releases: {}", e.getMessage());
            return List.of();
        }
    }

    private static UpgradeInfo info(String current, Map<String, Object> release, String version) {
        return new UpgradeInfo(current, version, VersionUtil.compare(version, current) > 0, releaseUrl(release));
    }

    private static String releaseUrl(Map<String, Object> release) {
        Object url = release.get("html_url");
        return url instanceof String text ? text : null;
    }

    static UpgradeInfo fromLatestRelease(String json, String current) {
        Map<String, Object> release = parseRelease(json);
        if (release == null) return new UpgradeInfo(current, null, false, null);
        String version = cliVersion(release);
        return version == null ? new UpgradeInfo(current, null, false, null) : info(current, release, version);
    }

    static UpgradeInfo fromReleaseList(String json, String current) {
        Map<String, Object> newest = null;
        String newestVersion = null;
        for (Map<String, Object> release : parseReleases(json)) {
            String version = cliVersion(release);
            if (version == null) continue;
            if (newestVersion == null || VersionUtil.compare(version, newestVersion) > 0) {
                newest = release;
                newestVersion = version;
            }
        }
        return newest == null ? new UpgradeInfo(current, null, false, null) : info(current, newest, newestVersion);
    }

    private final HttpClient httpClient;

    public UpgradeChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public UpgradeInfo check() {
        String current = VersionUtil.getCurrentVersion();
        try {
            UpgradeInfo latest = fromLatestRelease(get(RELEASES_LATEST_API), current);
            return latest.latestVersion() != null ? latest : fromReleaseList(get(RELEASES_API), current);
        } catch (IOException | InterruptedException e) {
            LOGGER.debug("Failed to check for updates: {}", e.getMessage());
            return new UpgradeInfo(current, null, false, null);
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "core-ai-cli")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    public record UpgradeInfo(String currentVersion, String latestVersion, boolean isNewer, String releaseUrl) {
        public String upgradeMessage() {
            if (!isNewer || latestVersion == null) {
                return "You are up to date (v" + currentVersion + ")";
            }
            return "New version available: v" + latestVersion + " (current: v" + currentVersion + ")";
        }
    }
}
