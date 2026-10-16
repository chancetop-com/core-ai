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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** Stand-in for the user skills root, so deletion tests never touch the developer's real {@code ~/.core-ai/skills}. */
    @TempDir
    Path userRoot;

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

    @Test
    void deleteRemovesAUserSkillDirectoryAndItsNamespace() throws Exception {
        var skill = userRoot.resolve("stephen/demo");
        Files.createDirectories(skill.resolve("references"));
        Files.writeString(skill.resolve("SKILL.md"), CONTENT);
        Files.writeString(skill.resolve("references/notes.md"), "notes");

        var outcome = LocalSkills.delete(workspace, Params.object().put("path", skill.toString()), userRoot);

        assertTrue(outcome.path("deleted").asBoolean());
        assertFalse(Files.exists(skill));
        assertFalse(Files.exists(userRoot.resolve("stephen")));
    }

    @Test
    void deleteRemovesAFlatMarkdownSkill() throws Exception {
        var flat = userRoot.resolve("scratch.md");
        Files.writeString(flat, CONTENT);

        LocalSkills.delete(workspace, Params.object().put("path", flat.toString()), userRoot);

        assertFalse(Files.exists(flat));
        assertTrue(Files.isDirectory(userRoot));
    }

    @Test
    void deleteRemovesAWorkspaceSkill() throws Exception {
        var skill = workspace.resolve(".core-ai/skills/local-one");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), CONTENT);

        LocalSkills.delete(workspace, Params.object().put("path", skill.toString()), userRoot);

        assertFalse(Files.exists(skill));
    }

    @Test
    void deleteRejectsPathsOutsideTheSkillRoots() throws Exception {
        var outside = workspace.resolve("secret.md");
        Files.writeString(outside, "secret");

        var error = assertThrows(RpcException.class,
                () -> LocalSkills.delete(workspace, Params.object().put("path", outside.toString()), userRoot));

        assertEquals("PATH_NOT_ALLOWED", error.data().path("code").asText());
        assertTrue(Files.exists(outside));
    }

    @Test
    void deleteRejectsPathsThatAreNotListedSkills() throws Exception {
        var notes = userRoot.resolve("notes");
        Files.createDirectories(notes);
        Files.writeString(notes.resolve("README.md"), "not a skill");

        var error = assertThrows(RpcException.class,
                () -> LocalSkills.delete(workspace, Params.object().put("path", notes.toString()), userRoot));
        assertEquals("NOT_A_SKILL", error.data().path("code").asText());

        var deepDir = userRoot.resolve("demo/references");
        Files.createDirectories(deepDir);
        var deep = deepDir.resolve("notes.md");
        Files.writeString(deep, "deep");
        var deepError = assertThrows(RpcException.class,
                () -> LocalSkills.delete(workspace, Params.object().put("path", deep.toString()), userRoot));
        assertEquals("NOT_A_SKILL", deepError.data().path("code").asText());
        assertTrue(Files.exists(deep));
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
