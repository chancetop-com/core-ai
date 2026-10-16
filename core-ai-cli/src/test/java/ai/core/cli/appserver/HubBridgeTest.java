package ai.core.cli.appserver;

import ai.core.api.server.hubcatalog.HubCatalogResponse;
import ai.core.api.server.hubcatalog.HubCatalogTool;
import ai.core.cli.hub.skill.SkillDigest;
import ai.core.cli.hub.skill.SkillHubClient;
import ai.core.cli.hub.skill.SkillHubMarker;
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

/**
 * Pull install paths of the hub bridge (no server involved): fresh install, up-to-date re-pull,
 * the confirmable conflict and the forced overwrite.
 *
 * @author stephen
 */
class HubBridgeTest {
    private static final String CONTENT = "---\nname: code-review\ndescription: review\n---\nbody";

    @TempDir
    Path tempDir;

    @Test
    void pullInstallsSkillAndWritesMarker() throws Exception {
        var digest = SkillDigest.of(CONTENT, null);
        var archive = new SkillHubClient.ArchiveResult(zip("SKILL.md", CONTENT), "id-1", digest, "stephen/code-review");

        var result = HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, false);

        assertEquals("installed", result.path("status").asText());
        assertEquals(1, result.path("files").asInt());
        assertEquals("stephen/code-review", result.path("qualifiedName").asText());
        var target = tempDir.resolve("stephen").resolve("code-review");
        assertEquals(CONTENT, Files.readString(target.resolve("SKILL.md")));
        var marker = SkillHubMarker.load(target);
        assertEquals("id-1", marker.id());
        assertEquals(digest, marker.digest());
        assertEquals("https://server", marker.server());
    }

    @Test
    void pullAgainIsUpToDate() throws Exception {
        var digest = SkillDigest.of(CONTENT, null);
        var archive = new SkillHubClient.ArchiveResult(zip("SKILL.md", CONTENT), "id-1", digest, "stephen/code-review");
        HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, false);

        var second = HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, false);

        assertEquals("up_to_date", second.path("status").asText());
        assertEquals(0, second.path("files").asInt());
    }

    @Test
    void modifiedLocalCopyAsksForConfirmationAndForceOverwrites() throws Exception {
        var digest = SkillDigest.of(CONTENT, null);
        var archive = new SkillHubClient.ArchiveResult(zip("SKILL.md", CONTENT), "id-1", digest, "stephen/code-review");
        HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, false);
        var target = tempDir.resolve("stephen").resolve("code-review");
        Files.writeString(target.resolve("SKILL.md"), "user edit");

        var conflict = assertThrows(RpcException.class,
                () -> HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, false));
        assertEquals("SKILL_CONFLICT", conflict.data().path("code").asText());
        assertEquals("user edit", Files.readString(target.resolve("SKILL.md")), "modified content must survive");

        var forced = HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, true);
        assertEquals("installed", forced.path("status").asText());
        assertEquals(CONTENT, Files.readString(target.resolve("SKILL.md")));
    }

    @Test
    void pullResolvesToThePushedWorkingCopy() throws Exception {
        var digest = SkillDigest.of(CONTENT, null);
        var workingCopy = tempDir.resolve("code-review");
        Files.createDirectories(workingCopy);
        Files.writeString(workingCopy.resolve("SKILL.md"), CONTENT);
        SkillHubMarker.write(workingCopy, SkillHubMarker.Marker.pushed("stephen/code-review", "id-1", digest, "https://server"));
        var archive = new SkillHubClient.ArchiveResult(zip("SKILL.md", CONTENT), "id-1", digest, "stephen/code-review");

        var result = HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, false);

        assertEquals("up_to_date", result.path("status").asText());
        assertEquals(workingCopy.toString(), result.path("dir").asText());
        assertFalse(Files.exists(tempDir.resolve("stephen").resolve("code-review")),
                "pulling a pushed skill must not create a second install");
    }

    @Test
    void modifiedWorkingCopySurvivesAPull() throws Exception {
        var digest = SkillDigest.of(CONTENT, null);
        var workingCopy = tempDir.resolve("code-review");
        Files.createDirectories(workingCopy);
        Files.writeString(workingCopy.resolve("SKILL.md"), CONTENT);
        SkillHubMarker.write(workingCopy, SkillHubMarker.Marker.pushed("stephen/code-review", "id-1", digest, "https://server"));
        Files.writeString(workingCopy.resolve("SKILL.md"), "user edit");
        var archive = new SkillHubClient.ArchiveResult(zip("SKILL.md", CONTENT), "id-1", digest, "stephen/code-review");

        var conflict = assertThrows(RpcException.class,
                () -> HubBridge.install(tempDir, "https://server", "stephen", "code-review", archive, false));

        assertEquals("SKILL_CONFLICT", conflict.data().path("code").asText());
        assertEquals("user edit", Files.readString(workingCopy.resolve("SKILL.md")));
    }

    @Test
    void catalogForwardsAgentRefIdForTheDesktop() {
        var response = new HubCatalogResponse();
        response.tools = List.of(agentTool());
        var node = Params.object();

        HubBridge.addTools(node, response);

        assertEquals("agent-123", node.path("tools").get(0).path("refId").asText());
    }

    @Test
    void certificateFailureIsReportedAsItsOwnReason() {
        var node = Params.object();
        var failure = new IllegalStateException("API request failed: (certificate_unknown) PKIX path building failed",
                new javax.net.ssl.SSLHandshakeException("PKIX path building failed"));

        var result = HubBridge.failure(node, "hub catalog fetch", failure);

        assertFalse(result.path("connected").asBoolean());
        assertEquals("untrusted_certificate", result.path("reason").asText());
    }

    private HubCatalogTool agentTool() {
        var tool = new HubCatalogTool();
        tool.kind = "agent";
        tool.name = "hr-assistant";
        tool.path = "hr-assistant";
        tool.refId = "agent-123";
        return tool;
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
