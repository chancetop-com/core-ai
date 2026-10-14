package ai.core.cli.appserver;

import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.api.server.skillhub.SkillHubSummary;
import ai.core.cli.hub.skill.SkillDigest;
import ai.core.cli.hub.skill.SkillHubClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skills panel inventory: hub provenance and freshness states, online and offline.
 *
 * @author stephen
 */
class LocalSkillsTest {
    private static final String CONTENT = "---\nname: demo\ndescription: demo\n---\nbody";

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
    Path workspace;

    @Test
    void hubInstallCarriesProvenanceAndLocalState() throws Exception {
        installDemo();

        var node = LocalSkills.list(workspace, null);

        var demo = find(node, "demo");
        assertTrue(demo.path("hub").asBoolean());
        assertEquals("stephen/demo", demo.path("qualifiedName").asText());
        assertEquals("unmodified", demo.path("state").asText());
        assertFalse(node.path("connected").asBoolean());
    }

    @Test
    void serverDigestMarksOutdated() throws Exception {
        installDemo();
        var client = new FakeClient(SkillDigest.of("changed", null));

        var node = LocalSkills.list(workspace, client);

        assertTrue(node.path("connected").asBoolean());
        assertEquals("outdated", find(node, "demo").path("state").asText());
    }

    @Test
    void localSkillWithoutMarkerStaysLocal() throws Exception {
        var dir = workspace.resolve(".core-ai/skills/scratch");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: scratch\ndescription: scratch\n---\nlocal");

        var node = LocalSkills.list(workspace, null);

        var scratch = find(node, "scratch");
        assertFalse(scratch.path("hub").asBoolean());
        assertEquals("local", scratch.path("state").asText());
    }

    private JsonNode find(JsonNode node, String name) {
        for (JsonNode skill : node.path("skills")) {
            if (name.equals(skill.path("name").asText())) {
                return skill;
            }
        }
        throw new AssertionError("skill not found in the panel list: " + name);
    }

    private void installDemo() throws IOException {
        var archive = new SkillHubClient.ArchiveResult(zip("SKILL.md", CONTENT), "id-1",
                SkillDigest.of(CONTENT, null), "stephen/demo");
        HubBridge.install(workspace.resolve(".core-ai/skills"), "https://server", "stephen", "demo", archive, false);
    }

    private static final class FakeClient extends SkillHubClient {
        private final String digest;

        FakeClient(String digest) {
            super("https://server", "key");
            this.digest = digest;
        }

        @Override
        public SkillHubSearchResponse search(String query, String namespace, String sourceType, Integer limit) {
            var summary = new SkillHubSummary();
            summary.qualifiedName = "stephen/demo";
            summary.digest = digest;
            var response = new SkillHubSearchResponse();
            response.skills = new ArrayList<>();
            response.skills.add(summary);
            return response;
        }
    }
}
