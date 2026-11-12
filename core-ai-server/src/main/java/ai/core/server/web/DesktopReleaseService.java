package ai.core.server.web;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the newest core-ai-desktop installer for a platform. Desktop builds share the core-ai repository with
 * the CLI and sandbox release streams and stay marked prerelease, so the repo-wide "latest" release never represents
 * them: the release list is filtered by the desktop-v* tag and the newest numeric version wins.
 *
 * @author Stephen
 */
public class DesktopReleaseService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DesktopReleaseService.class);
    static final String FALLBACK_URL = "https://github.com/chancetop-com/core-ai/releases";
    private static final String RELEASES_API = "https://api.github.com/repos/chancetop-com/core-ai/releases?per_page=100";
    private static final Pattern DESKTOP_TAG = Pattern.compile("^desktop-v(\\d+(?:\\.\\d+)*)$");
    private static final String WINDOWS = "windows";
    private static final String MACOS = "macos";
    private static final Map<String, String> ASSET_NAMES = Map.of(
            WINDOWS, "core-ai-desktop-win-x64.exe",
            MACOS, "core-ai-desktop-mac-arm64.dmg");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(1);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    static String platform(String requested, String userAgent) {
        if (requested != null && !requested.isBlank()) {
            return switch (requested.toLowerCase(Locale.ROOT)) {
                case "windows", "win" -> WINDOWS;
                case "macos", "mac", "darwin" -> MACOS;
                default -> null;
            };
        }
        if (userAgent == null) return null;
        if (userAgent.contains("Windows")) return WINDOWS;
        if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS X")) return MACOS;
        return null;
    }

    /**
     * @return asset download URL by platform; empty when no published desktop release carries the platform asset
     */
    static Map<String, String> selectAssets(String releaseListJson) throws Exception {
        JsonNode releases = JsonUtil.OBJECT_MAPPER.readTree(releaseListJson);
        if (!releases.isArray()) return Map.of();

        String bestVersion = null;
        JsonNode bestRelease = null;
        for (var release : releases) {
            if (release.path("draft").asBoolean(false)) continue;
            var tag = release.path("tag_name").asText("");
            var match = DESKTOP_TAG.matcher(tag);
            if (!match.matches()) continue;
            var version = match.group(1);
            if (bestVersion == null || compareVersions(version, bestVersion) > 0) {
                bestVersion = version;
                bestRelease = release;
            }
        }
        if (bestRelease == null) return Map.of();

        Map<String, String> byName = new HashMap<>();
        for (var asset : bestRelease.path("assets")) {
            var name = asset.path("name").asText("");
            var url = asset.path("browser_download_url").asText("");
            if (!name.isEmpty() && !url.isEmpty()) byName.put(name, url);
        }
        Map<String, String> selected = new HashMap<>();
        ASSET_NAMES.forEach((platform, assetName) -> {
            var url = byName.get(assetName);
            if (url != null) selected.put(platform, url);
        });
        return selected;
    }

    private static int compareVersions(String left, String right) {
        var a = left.split("\\.");
        var b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int diff = Integer.compare(part(a, i), part(b, i));
            if (diff != 0) return diff;
        }
        return 0;
    }

    private static int part(String[] parts, int index) {
        return index < parts.length ? Integer.parseInt(parts[index]) : 0;
    }

    private final Object refreshLock = new Object();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile CachedAssets cache;

    /**
     * @param platform normalized by {@link #platform(String, String)}, null when the caller's OS is unknown
     */
    public String downloadUrl(String platform) {
        if (platform == null) return FALLBACK_URL;
        var url = assets().get(platform);
        return url != null ? url : FALLBACK_URL;
    }

    protected String fetchReleaseList() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_API))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "core-ai-server")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub releases API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private Map<String, String> assets() {
        var current = cache;
        if (current != null && current.fresh()) return current.assets();
        return refresh().assets();
    }

    private CachedAssets refresh() {
        synchronized (refreshLock) {
            var current = cache;
            if (current != null && current.fresh()) return current;
            try {
                var assets = selectAssets(fetchReleaseList());
                var refreshed = new CachedAssets(assets, Instant.now().plus(CACHE_TTL));
                cache = refreshed;
                return refreshed;
            } catch (Exception e) {
                LOGGER.warn("failed to resolve the latest core-ai-desktop release, the download will fall back to the releases page: {}", e.getMessage());
                if (current != null) return current;
                var failed = new CachedAssets(Map.of(), Instant.now().plus(FAILURE_TTL));
                cache = failed;
                return failed;
            }
        }
    }

    private record CachedAssets(Map<String, String> assets, Instant expiresAt) {
        boolean fresh() {
            return expiresAt.isAfter(Instant.now());
        }
    }
}
