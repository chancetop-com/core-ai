package ai.core.cli.hub.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillHubMarkerTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripPersistsAllFields() {
        var marker = new SkillHubMarker.Marker("stephen/code-review", "id-1", "digest-1", "https://server", "2026-09-04T00:00:00Z");

        SkillHubMarker.write(tempDir, marker);

        assertEquals(marker, SkillHubMarker.load(tempDir));
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
    }

    @Test
    void markerFileIsHiddenFromResourceScans() throws Exception {
        var skillDir = tempDir.resolve("code-review");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\ndescription: review\n---\n");
        Files.writeString(skillDir.resolve("references/style.md"), "style");
        SkillHubMarker.write(skillDir, new SkillHubMarker.Marker("stephen/code-review", "id-1", "d1", "srv", "ts"));

        var local = new LocalSkillScanner().scan(tempDir).getFirst();
        assertEquals(1, local.resources().size(), ".skill-hub.json must be excluded from resource scans");
        assertEquals("references/style.md", local.resources().getFirst());
    }
}
