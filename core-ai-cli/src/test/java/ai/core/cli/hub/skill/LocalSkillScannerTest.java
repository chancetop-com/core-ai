package ai.core.cli.hub.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSkillScannerTest {
    @TempDir
    Path tempDir;

    private final LocalSkillScanner scanner = new LocalSkillScanner();

    @Test
    void scansNamespacedLayout() throws Exception {
        var skillDir = tempDir.resolve("stephen").resolve("code-review");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\ndescription: review code\n---\n");
        Files.writeString(skillDir.resolve("scripts/check.sh"), "#!/bin/sh");

        var skills = scanner.scan(tempDir);

        assertEquals(1, skills.size());
        var skill = skills.getFirst();
        assertEquals("code-review", skill.name());
        assertEquals("stephen/code-review", skill.qualifiedName());
        assertEquals("review code", skill.description());
        assertEquals(1, skill.resources().size());
        assertNotEquals("", skill.digest());
    }

    @Test
    void scansFlatLayout() throws Exception {
        var skillDir = tempDir.resolve("code-review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\ndescription: review code\n---\n");

        var skills = scanner.scan(tempDir);

        assertEquals(1, skills.size());
        assertEquals("code-review", skills.getFirst().qualifiedName(), "flat layout has no namespace");
    }

    @Test
    void unparseableSkillIsSkipped() throws Exception {
        var skillDir = tempDir.resolve("broken");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "no frontmatter here");

        assertTrue(scanner.scan(tempDir).isEmpty());
    }

    @Test
    void missingRootScansEmpty() {
        assertTrue(scanner.scan(tempDir.resolve("nope")).isEmpty());
    }

    @Test
    void dotPrefixedEntriesAreExcludedFromDigest() throws Exception {
        var skillDir = tempDir.resolve("code-review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: code-review\ndescription: review\n---\n");
        Files.writeString(skillDir.resolve(".hidden"), "secret");
        SkillHubMarker.write(skillDir, new SkillHubMarker.Marker("stephen/code-review", "id", "digest", "server", "ts"));

        var skill = scanner.scan(tempDir).getFirst();
        assertEquals(0, skill.resources().size(), "dot entries must not count as resources");
        assertEquals(SkillDigest.of("---\nname: code-review\ndescription: review\n---\n", null), skill.digest());
    }
}
