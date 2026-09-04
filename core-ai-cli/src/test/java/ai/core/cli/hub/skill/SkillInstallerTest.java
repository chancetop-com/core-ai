package ai.core.cli.hub.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillInstallerTest {
    @TempDir
    Path tempDir;

    private SkillInstaller installer;
    private String digest;

    @BeforeEach
    void setUp() {
        installer = new SkillInstaller();
        var content = "---\nname: code-review\ndescription: review\n---\nbody";
        var resources = List.of(new SkillDigest.Resource("scripts/check.sh", "#!/bin/sh\n"));
        digest = SkillDigest.of(content, resources);
    }

    private SkillHubMarker.Marker source(String digest) {
        return new SkillHubMarker.Marker("stephen/code-review", "id-1", digest, "https://server", null);
    }

    @Test
    void freshInstallWritesFilesAndMarker() throws Exception {
        var target = tempDir.resolve("code-review");
        byte[] zip = zip("SKILL.md", "---\nname: code-review\ndescription: review\n---\nbody",
                "scripts/check.sh", "#!/bin/sh\n");

        var outcome = installer.install(target, zip, source(digest), false);

        assertTrue(outcome.replaced());
        assertEquals(2, outcome.files());
        assertTrue(Files.isRegularFile(target.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(target.resolve("scripts/check.sh")));
        var marker = SkillHubMarker.load(target);
        assertEquals("stephen/code-review", marker.qualifiedName());
        assertEquals("id-1", marker.id());
        assertEquals(digest, marker.digest());
    }

    @Test
    void sameDigestIsUpToDateWithoutRewrite() throws Exception {
        var target = tempDir.resolve("code-review");
        byte[] zip = zip("SKILL.md", "---\nname: code-review\ndescription: review\n---\nbody", "scripts/check.sh", "#!/bin/sh\n");
        installer.install(target, zip, source(digest), false);

        var second = installer.install(target, zip, source(digest), false);

        assertFalse(second.replaced());
        assertEquals(0, second.files());
    }

    @Test
    void outdatedDigestIsReplacedWithoutForce() throws Exception {
        var target = tempDir.resolve("code-review");
        byte[] zip = zip("SKILL.md", "---\nname: code-review\ndescription: review\n---\nbody", "scripts/check.sh", "#!/bin/sh\n");
        installer.install(target, zip, source(digest), false);

        String newContent = "---\nname: code-review\ndescription: review\n---\nnew body";
        var newDigest = SkillDigest.of(newContent, null);
        var outcome = installer.install(target, zip("SKILL.md", newContent), source(newDigest), false);

        assertTrue(outcome.replaced());
        assertEquals(newContent, Files.readString(target.resolve("SKILL.md")));
        assertEquals(newDigest, SkillHubMarker.load(target).digest());
    }

    @Test
    void locallyModifiedCopyRequiresForce() throws Exception {
        var target = tempDir.resolve("code-review");
        byte[] zip = zip("SKILL.md", "---\nname: code-review\ndescription: review\n---\nbody", "scripts/check.sh", "#!/bin/sh\n");
        installer.install(target, zip, source(digest), false);
        Files.writeString(target.resolve("SKILL.md"), "user edit");

        String newDigest = SkillDigest.of("---\nname: code-review\ndescription: review\n---\nnew body", null);
        assertThrows(IllegalStateException.class,
                () -> installer.install(target, zip("SKILL.md", "---\nname: code-review\ndescription: review\n---\nnew body"),
                        source(newDigest), false));
        assertEquals("user edit", Files.readString(target.resolve("SKILL.md")), "modified content must survive");

        var forced = installer.install(target, zip("SKILL.md", "---\nname: code-review\ndescription: review\n---\nnew body"),
                source(newDigest), true);
        assertTrue(forced.replaced());
    }

    @Test
    void unmanagedDirectoryRequiresForce() throws Exception {
        var target = tempDir.resolve("code-review");
        Files.createDirectories(target);
        Files.writeString(target.resolve("SKILL.md"), "---\nname: code-review\ndescription: mine\n---\n");

        byte[] zip = zip("SKILL.md", "---\nname: code-review\ndescription: review\n---\nbody", "scripts/check.sh", "#!/bin/sh\n");
        assertThrows(IllegalStateException.class,
                () -> installer.install(target, zip, source(digest), false));

        var forced = installer.install(target, zip, source(digest), true);
        assertTrue(forced.replaced());
    }

    @Test
    void zipSlipEntriesAreRejected() throws Exception {
        var target = tempDir.resolve("code-review");
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../evil.sh"));
            zip.write("evil".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IllegalStateException.class,
                () -> installer.install(target, bytes.toByteArray(), source(digest), false));
        assertFalse(Files.exists(target.resolve("SKILL.md")), "nothing may be written from a rejected archive");
        assertFalse(Files.exists(target.resolve("../evil.sh")));
    }

    private byte[] zip(String... nameContentPairs) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameContentPairs[i]));
                zip.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
