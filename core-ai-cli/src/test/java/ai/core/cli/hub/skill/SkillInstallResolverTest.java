package ai.core.cli.hub.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillInstallResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void pushedWorkingCopyIsTheInstallTarget() throws Exception {
        var workingCopy = linkedDir("demo", "stephen/demo", "id-1", "https://server");

        var target = new SkillInstallResolver().resolveTarget(tempDir, "https://server", "id-1", "stephen/demo");

        assertEquals(workingCopy, target, "a pull must resolve to the pushed working copy");
    }

    @Test
    void canonicalLocationIsTheFallback() {
        var target = new SkillInstallResolver().resolveTarget(tempDir, "https://server", "id-1", "stephen/demo");

        assertEquals(tempDir.resolve("stephen").resolve("demo"), target);
    }

    @Test
    void canonicalMarkedInstallWins() throws Exception {
        var workingCopy = linkedDir("demo", "stephen/demo", "id-1", "https://server");
        linkedDir(Path.of("stephen", "demo").toString(), "stephen/demo", "id-1", "https://server");
        Files.writeString(workingCopy.resolve("SKILL.md"),
                "---\nname: demo\ndescription: local edit\n---\n");

        var found = new SkillInstallResolver().find(tempDir, "https://server", "id-1", "stephen/demo");

        assertEquals(tempDir.resolve("stephen").resolve("demo"), found.skillDir());
    }

    @Test
    void copyLinkedToAnotherServerIsIgnored() throws Exception {
        linkedDir("demo", "stephen/demo", "id-1", "https://other-server");

        var found = new SkillInstallResolver().find(tempDir, "https://server", "id-1", "stephen/demo");
        var target = new SkillInstallResolver().resolveTarget(tempDir, "https://server", "id-1", "stephen/demo");

        assertNull(found);
        assertEquals(tempDir.resolve("stephen").resolve("demo"), target);
    }

    @Test
    void qualifiedNameMatchesWhenServerHasNoId() throws Exception {
        var workingCopy = linkedDir("demo", "stephen/demo", "id-1", "https://server");

        var target = new SkillInstallResolver().resolveTarget(tempDir, "https://server", null, "stephen/demo");

        assertEquals(workingCopy, target);
    }

    @Test
    void unmarkedSameNameDirectoryIsNotLinked() throws Exception {
        var plain = tempDir.resolve("demo");
        Files.createDirectories(plain);
        Files.writeString(plain.resolve("SKILL.md"), "---\nname: demo\ndescription: plain\n---\n");

        var found = new SkillInstallResolver().find(tempDir, "https://server", "id-1", "stephen/demo");

        assertNull(found);
    }

    private Path linkedDir(String name, String qualifiedName, String id, String server) throws Exception {
        var dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: demo\ndescription: demo\n---\n");
        SkillHubMarker.write(dir, SkillHubMarker.Marker.pulled(qualifiedName, id, "digest", server));
        return dir;
    }
}
