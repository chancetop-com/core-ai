package ai.core.cli.upgrade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeCheckerTest {

    private static final String SANDBOX_LATEST = """
            {
              "tag_name": "sandbox-v1.0.47",
              "name": "Sandbox runtime v1.0.47",
              "prerelease": false,
              "html_url": "https://github.com/chancetop-com/core-ai/releases/tag/sandbox-v1.0.47"
            }""";

    @Test
    void picksHighestCliVersionFromReleaseListIgnoringOtherStreams() {
        String releases = """
                [
                  {"tag_name": "sandbox-v2.1.0", "prerelease": false},
                  {"tag_name": "v2.0.15", "prerelease": false},
                  {"tag_name": "sandbox-v1.0.47", "prerelease": false},
                  {"tag_name": "v2.0.16", "prerelease": false}
                ]""";

        var info = UpgradeChecker.fromReleaseList(releases, "2.0.15");

        assertEquals("2.0.16", info.latestVersion());
        assertTrue(info.isNewer());
    }

    @Test
    void ignoresLatestReleaseFromAnotherStream() {
        var info = UpgradeChecker.fromLatestRelease(SANDBOX_LATEST, "2.0.16");

        assertNull(info.latestVersion());
        assertFalse(info.isNewer());
    }

    @Test
    void usesLatestReleaseWhenItIsACliRelease() {
        var info = UpgradeChecker.fromLatestRelease("""
                {
                  "tag_name": "v2.0.16",
                  "html_url": "https://github.com/chancetop-com/core-ai/releases/tag/v2.0.16"
                }""", "2.0.15");

        assertEquals("2.0.16", info.latestVersion());
        assertTrue(info.isNewer());
        assertEquals("https://github.com/chancetop-com/core-ai/releases/tag/v2.0.16", info.releaseUrl());
    }

    @Test
    void reportsUpToDateWhenRunningTheNewestCliVersion() {
        assertFalse(UpgradeChecker.fromLatestRelease("{\"tag_name\": \"v2.0.16\"}", "2.0.16").isNewer());
        assertFalse(UpgradeChecker.fromReleaseList("[{\"tag_name\": \"v2.0.16\"}]", "2.0.16").isNewer());
        assertFalse(UpgradeChecker.fromReleaseList("[{\"tag_name\": \"v2.0.16\"}]", "2.0.17").isNewer());
    }

    @Test
    void skipsPrereleaseCliReleases() {
        String releases = """
                [
                  {"tag_name": "v2.1.0-rc.1", "prerelease": false},
                  {"tag_name": "v2.1.0", "prerelease": true},
                  {"tag_name": "v2.0.16", "prerelease": false}
                ]""";

        var info = UpgradeChecker.fromReleaseList(releases, "2.0.16");

        assertEquals("2.0.16", info.latestVersion());
        assertFalse(info.isNewer());
    }

    @Test
    void treatsUnknownPayloadAsUnknown() {
        assertNull(UpgradeChecker.fromLatestRelease("not json", "2.0.16").latestVersion());
        assertNull(UpgradeChecker.fromLatestRelease(null, "2.0.16").latestVersion());
        assertNull(UpgradeChecker.fromReleaseList("not json", "2.0.16").latestVersion());
        assertNull(UpgradeChecker.fromReleaseList(null, "2.0.16").latestVersion());
        assertNull(UpgradeChecker.fromReleaseList("[{\"tag_name\": \"sandbox-v1.0.47\"}]", "2.0.16").latestVersion());
    }
}
