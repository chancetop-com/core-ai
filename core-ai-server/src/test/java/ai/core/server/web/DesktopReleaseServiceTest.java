package ai.core.server.web;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopReleaseServiceTest {
    private static final String RELEASES = """
            [
              {"tag_name": "v2.0.196", "draft": false, "assets": [
                {"name": "core-ai-cli-win-x64.zip", "browser_download_url": "https://github.com/cli-win"}
              ]},
              {"tag_name": "desktop-v0.1.9", "draft": false, "assets": [
                {"name": "core-ai-desktop-win-x64.exe", "browser_download_url": "https://github.com/win-0.1.9"},
                {"name": "core-ai-desktop-mac-arm64.dmg", "browser_download_url": "https://github.com/mac-0.1.9"}
              ]},
              {"tag_name": "desktop-v0.1.50", "draft": false, "assets": [
                {"name": "core-ai-desktop-win-x64.exe", "browser_download_url": "https://github.com/win-0.1.50"},
                {"name": "core-ai-desktop-mac-arm64.dmg", "browser_download_url": "https://github.com/mac-0.1.50"},
                {"name": "latest.yml", "browser_download_url": "https://github.com/latest-0.1.50"}
              ]},
              {"tag_name": "desktop-v0.1.51", "draft": true, "assets": [
                {"name": "core-ai-desktop-win-x64.exe", "browser_download_url": "https://github.com/win-0.1.51"}
              ]}
            ]
            """;

    @Test
    void picksNewestDesktopReleaseByNumericVersion() throws Exception {
        var assets = DesktopReleaseService.selectAssets(RELEASES);

        assertEquals("https://github.com/win-0.1.50", assets.get("windows"));
        assertEquals("https://github.com/mac-0.1.50", assets.get("macos"));
    }

    @Test
    void ignoresRepositoriesWithoutPublishedDesktopRelease() throws Exception {
        var assets = DesktopReleaseService.selectAssets("""
                [{"tag_name": "v2.0.196", "draft": false, "assets": []}]
                """);

        assertTrue(assets.isEmpty());
    }

    @Test
    void detectsPlatformFromQueryThenUserAgent() {
        assertEquals("windows", DesktopReleaseService.platform("win", null));
        assertEquals("macos", DesktopReleaseService.platform("macOS", null));
        assertEquals("windows", DesktopReleaseService.platform(null, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"));
        assertEquals("macos", DesktopReleaseService.platform(null, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"));
        assertNull(DesktopReleaseService.platform(null, "Mozilla/5.0 (X11; Linux x86_64)"));
        assertNull(DesktopReleaseService.platform("linux", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"));
    }

    @Test
    void fallsBackToReleasesPageForUnknownPlatform() {
        assertEquals(DesktopReleaseService.FALLBACK_URL, new StubService(() -> RELEASES).downloadUrl(null));
    }

    @Test
    void cachesResolvedAssets() {
        var service = new StubService(() -> RELEASES);

        assertEquals("https://github.com/win-0.1.50", service.downloadUrl("windows"));
        assertEquals("https://github.com/win-0.1.50", service.downloadUrl("windows"));
        assertEquals(1, service.calls);
    }

    @Test
    void fallsBackToReleasesPageWhenLookupFails() {
        var service = new StubService(() -> {
            throw new IllegalStateException("GitHub unavailable");
        });

        assertEquals(DesktopReleaseService.FALLBACK_URL, service.downloadUrl("windows"));
        assertEquals(DesktopReleaseService.FALLBACK_URL, service.downloadUrl("windows"));
        assertEquals(1, service.calls);
    }

    @Test
    void fallsBackToReleasesPageWhenReleaseHasNoAssetForPlatform() throws Exception {
        var service = new StubService(() -> """
                [{"tag_name": "desktop-v0.2.0", "draft": false, "assets": [
                  {"name": "core-ai-desktop-mac-arm64.dmg", "browser_download_url": "https://github.com/mac-0.2.0"}
                ]}]
                """);

        assertEquals(DesktopReleaseService.FALLBACK_URL, service.downloadUrl("windows"));
        assertEquals("https://github.com/mac-0.2.0", service.downloadUrl("macos"));
    }

    private static class StubService extends DesktopReleaseService {
        private final Supplier<String> releaseList;
        private int calls;

        StubService(Supplier<String> releaseList) {
            this.releaseList = releaseList;
        }

        @Override
        protected String fetchReleaseList() {
            calls++;
            return releaseList.get();
        }
    }
}
