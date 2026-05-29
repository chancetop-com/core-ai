package ai.core.cli.upgrade;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public final class UpgradeChecker {

    private static final String RELEASES_API = "https://api.github.com/repos/chancetop-com/core-ai/releases/latest";
    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    private static final Pattern HTML_URL_PATTERN = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient;

    private static String extractTag(String json) {
        Matcher m = TAG_PATTERN.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractHtmlUrl(String json) {
        Matcher m = HTML_URL_PATTERN.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public UpgradeChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public UpgradeInfo check() {
        String current = VersionUtil.getCurrentVersion();
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_API))
                    .header("Accept", "application/json")
                    .header("User-Agent", "core-ai-cli")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new UpgradeInfo(current, null, false, null);
            }
            String body = response.body();
            String latest = extractTag(body);
            String url = extractHtmlUrl(body);
            if (latest == null) {
                return new UpgradeInfo(current, null, false, null);
            }
            boolean isNewer = VersionUtil.compare(latest, current) > 0;
            return new UpgradeInfo(current, latest, isNewer, url);
        } catch (IOException | InterruptedException e) {
            return new UpgradeInfo(current, null, false, null);
        }
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
