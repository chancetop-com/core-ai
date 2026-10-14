package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.api.server.skillhub.SkillHubSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Update paths of the shared updater (no server involved): outdated replaced, up-to-date and
 * locally modified skipped, force overwriting, and the not-installed hint.
 *
 * @author stephen
 */
class SkillUpdaterTest {
    private static final String V1 = "---\nname: demo\ndescription: v1\n---\nbody v1";
    private static final String V2 = "---\nname: demo\ndescription: v2\n---\nbody v2";

    private static byte[] zip(String name, String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @TempDir
    Path tempDir;

    @Test
    void outdatedSkillIsReplaced() throws Exception {
        installV1();
        var client = new FakeClient(Map.of("stephen/demo", SkillDigest.of(V2, null)), V2);

        var outcomes = new SkillUpdater().update(client, tempDir, "stephen/demo", false);

        assertEquals(1, outcomes.size());
        assertTrue(outcomes.get(0).updated());
        assertNull(outcomes.get(0).reason());
        assertEquals(V2, Files.readString(skillDir().resolve("SKILL.md")));
        assertEquals(SkillDigest.of(V2, null), SkillHubMarker.load(skillDir()).digest());
    }

    @Test
    void upToDateSkillIsSkipped() throws Exception {
        installV1();
        var client = new FakeClient(Map.of("stephen/demo", SkillDigest.of(V1, null)), V2);

        var outcomes = new SkillUpdater().update(client, tempDir, "stephen/demo", false);

        assertFalse(outcomes.get(0).updated());
        assertEquals("(already up to date)", outcomes.get(0).reason());
        assertEquals(V1, Files.readString(skillDir().resolve("SKILL.md")));
    }

    @Test
    void modifiedSkillIsSkippedUnlessForced() throws Exception {
        installV1();
        Files.writeString(skillDir().resolve("SKILL.md"), "user edit");
        var client = new FakeClient(Map.of("stephen/demo", SkillDigest.of(V2, null)), V2);

        var skipped = new SkillUpdater().update(client, tempDir, "stephen/demo", false);
        assertFalse(skipped.get(0).updated());
        assertEquals("(modified locally, pass --force to overwrite)", skipped.get(0).reason());
        assertEquals("user edit", Files.readString(skillDir().resolve("SKILL.md")));

        var forced = new SkillUpdater().update(client, tempDir, "stephen/demo", true);
        assertTrue(forced.get(0).updated());
        assertEquals(V2, Files.readString(skillDir().resolve("SKILL.md")));
    }

    @Test
    void unknownSkillReportsThePullHint() {
        var client = new FakeClient(Map.of(), V2);

        var outcomes = new SkillUpdater().update(client, tempDir, "stephen/nope", false);

        assertFalse(outcomes.get(0).updated());
        assertEquals("(not installed, use pull)", outcomes.get(0).reason());
    }

    @Test
    void updateAllWalksHubMarkedSkillsOnly() throws Exception {
        installV1();
        var plain = tempDir.resolve("plain");
        Files.createDirectories(plain);
        Files.writeString(plain.resolve("SKILL.md"), "---\nname: plain\ndescription: plain\n---\nlocal");
        var client = new FakeClient(Map.of("stephen/demo", SkillDigest.of(V2, null)), V2);

        var outcomes = new SkillUpdater().updateAll(client, tempDir, false);

        assertEquals(1, outcomes.size());
        assertEquals("stephen/demo", outcomes.get(0).qualifiedName());
        assertTrue(outcomes.get(0).updated());
    }

    private Path skillDir() {
        return tempDir.resolve("stephen").resolve("demo");
    }

    private void installV1() throws IOException {
        var source = new SkillHubMarker.Marker("stephen/demo", "id-1", SkillDigest.of(V1, null), "https://server", null);
        new SkillInstaller().install(skillDir(), zip("SKILL.md", V1), source, false);
    }

    private static final class FakeClient extends SkillHubClient {
        private final Map<String, String> digests;
        private final String content;

        FakeClient(Map<String, String> digests, String content) {
            super("https://server", "key");
            this.digests = digests;
            this.content = content;
        }

        @Override
        public SkillHubSearchResponse search(String query, String namespace, String sourceType, Integer limit) {
            var response = new SkillHubSearchResponse();
            var skills = new ArrayList<SkillHubSummary>();
            digests.forEach((qualified, digest) -> {
                var summary = new SkillHubSummary();
                summary.qualifiedName = qualified;
                summary.digest = digest;
                skills.add(summary);
            });
            response.skills = skills;
            return response;
        }

        @Override
        public ArchiveResult archive(String namespace, String name) {
            try {
                return new ArchiveResult(zip("SKILL.md", content), "id-2", SkillDigest.of(content, null), namespace + "/" + name);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
