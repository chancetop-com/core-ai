package ai.core.cli.hub.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillHubMarkerTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripPersistsAllFields() {
        var marker = new SkillHubMarker.Marker("stephen/code-review", "id-1", "digest-1", "https://server",
                "2026-09-04T00:00:00Z", null);

        SkillHubMarker.write(tempDir, marker);

        assertEquals(marker, SkillHubMarker.load(tempDir));
    }

    @Test
    void pushedMarkerStampsPushedAt() {
        var marker = SkillHubMarker.Marker.pushed("stephen/code-review", "id-1", "digest-1", "https://server/");

        assertNull(marker.pulledAt());
        assertNotNull(marker.pushedAt());
        assertEquals("https://server", marker.server(), "trailing slash is normalized away");
    }

    @Test
    void linkAfterPushWritesMarkerFromUploadResponse() {
        var body = "{\"id\":\"id-9\",\"qualified_name\":\"stephen/demo\",\"digest\":null}";

        assertTrue(SkillHubMarker.linkAfterPush(tempDir, body, "https://server", "local-digest"));

        var marker = SkillHubMarker.load(tempDir);
        assertEquals("stephen/demo", marker.qualifiedName());
        assertEquals("id-9", marker.id());
        assertEquals("local-digest", marker.digest());
        assertEquals("https://server", marker.server());
        assertNotNull(marker.pushedAt());
    }

    @Test
    void linkAfterPushIsBestEffort() {
        assertFalse(SkillHubMarker.linkAfterPush(tempDir, "not json", "https://server", "d"));
        assertFalse(SkillHubMarker.linkAfterPush(tempDir, "{\"id\":\"id-9\"}", "https://server", "d"));
        assertNull(SkillHubMarker.load(tempDir));
    }

    @Test
    void missingFileLoadsNull() {
        assertNull(SkillHubMarker.load(tempDir));
    }

    @Test
    void partiallyMissingFieldsSurviveLoad() throws Exception {
        Files.writeString(tempDir.resolve(SkillHubMarker.FILE_NAME), "{\"digest\":\"d1\"}");
        var marker = SkillHubMarker.load(tempDir);
        assertEquals("d1", marker.digest());
        assertNull(marker.qualifiedName());
        assertNull(marker.pushedAt());
    }

    @Test
    void markerFileIsHiddenFromResourceScans() throws Exception {
        var skillDir = tempDir.resolve("code-review");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\ndescription: review\n---\n");
        Files.writeString(skillDir.resolve("references/style.md"), "style");
        SkillHubMarker.write(skillDir, SkillHubMarker.Marker.pulled("stephen/code-review", "id-1", "d1", "srv"));

        var local = new LocalSkillScanner().scan(tempDir).getFirst();
        assertEquals(1, local.resources().size(), ".skill-hub.json must be excluded from resource scans");
        assertEquals("references/style.md", local.resources().getFirst());
    }
}
