package ai.core.cli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTrustTest {

    @TempDir
    Path temp;

    private Path workspace() throws IOException {
        return Files.createDirectories(temp.resolve("workspace"));
    }

    private Path trustFile() {
        return temp.resolve("plugin-store").resolve("workspace-trust.json");
    }

    private void writeTrust(Path workspace, String fingerprint) throws IOException {
        Files.createDirectories(trustFile().getParent());
        var json = """
                {"schemaVersion": "1", "workspaces": {"%s": {"fingerprint": "%s", "trustedAt": "2026-11-17T00:00:00Z"}}}
                """.formatted(WorkspaceTrust.canonicalKey(workspace).replace("\\", "\\\\"), fingerprint);
        Files.writeString(trustFile(), json);
    }

    @Test
    void anEmptyWorkspaceNeedsNoTrustRecord() throws IOException {
        var evaluation = WorkspaceTrust.evaluate(workspace(), trustFile(), WorkspaceTrust.Mode.CLI);

        assertEquals(WorkspaceTrust.State.TRUSTED, evaluation.state());
        assertFalse(evaluation.hasExecutableInputs());
        assertEquals("no_workspace_config", evaluation.reason());
    }

    @Test
    void aWorkspaceHookRequiresTrustAndAMatchingFingerprint() throws IOException {
        var workspace = workspace();
        var hooks = workspace.resolve(".core-ai/hooks.json");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "{\"hooks\":{}}");

        var untrusted = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI);
        assertEquals(WorkspaceTrust.State.RESTRICTED, untrusted.state());
        assertEquals("not_trusted", untrusted.reason());
        assertTrue(untrusted.hasExecutableInputs());

        writeTrust(workspace, untrusted.fingerprint());
        var trusted = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI);
        assertEquals(WorkspaceTrust.State.TRUSTED, trusted.state());
        assertEquals(untrusted.fingerprint(), trusted.fingerprint());
    }

    @Test
    void changingAnExecutableInputInvalidatesTheRecord() throws IOException {
        var workspace = workspace();
        var hooks = workspace.resolve(".core-ai/hooks.json");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "{\"hooks\":{\"SessionStart\":[]}}");
        writeTrust(workspace, WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI).fingerprint());

        Files.writeString(hooks, "{\"hooks\":{\"SessionStart\":[{\"command\":\"echo hi\"}]}}");

        var evaluation = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI);
        assertEquals(WorkspaceTrust.State.RESTRICTED, evaluation.state());
        assertEquals("fingerprint_changed", evaluation.reason());
    }

    @Test
    void workspaceSkillsAndPluginsAreTrustInputs() throws IOException {
        var workspace = workspace();
        var skill = workspace.resolve(".core-ai/skills/demo/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "# demo");

        assertTrue(WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI).hasExecutableInputs());

        var legacyPlugin = workspace.resolve(".core-ai/plugins/demo/hooks/hooks.json");
        Files.createDirectories(legacyPlugin.getParent());
        Files.writeString(legacyPlugin, "{}");
        var fingerprint = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI).fingerprint();
        writeTrust(workspace, fingerprint);
        assertEquals(WorkspaceTrust.State.TRUSTED, WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI).state());

        Files.writeString(legacyPlugin, "{\"hooks\":{}}");
        assertEquals("fingerprint_changed", WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI).reason());
    }

    @Test
    void aRecordForAnotherWorkspaceKeyDoesNotTrustThisOne() throws IOException {
        var workspace = workspace();
        var hooks = workspace.resolve(".core-ai/agent.properties");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "agent.max.turn=5");

        writeTrust(temp.resolve("elsewhere"), WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI).fingerprint());

        assertEquals(WorkspaceTrust.State.RESTRICTED, WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI).state());
    }

    @Test
    void desktopModeIgnoresCliMcpFilesButKeepsThemForCli() throws IOException {
        var workspace = workspace();
        var mcp = workspace.resolve(".core-ai/MCP.json");
        Files.createDirectories(mcp.getParent());
        Files.writeString(mcp, "{\"mcpServers\":{}}");

        var cli = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI);
        assertEquals(WorkspaceTrust.State.RESTRICTED, cli.state());
        assertTrue(cli.hasExecutableInputs());

        var desktop = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.DESKTOP_MCP);
        assertEquals(WorkspaceTrust.State.TRUSTED, desktop.state());
        assertFalse(desktop.hasExecutableInputs());
        assertEquals("no_workspace_config", desktop.reason());
        assertEquals(WorkspaceTrust.canonicalKey(workspace) + "#desktop-mcp", desktop.key());
    }

    @Test
    void desktopModeReportsLegacyMigrationWithoutWritingTrustFile() throws IOException {
        var workspace = workspace();
        var hooks = workspace.resolve(".core-ai/hooks.json");
        var mcp = workspace.resolve(".core-ai/MCP.json");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "{\"hooks\":{}}");
        Files.writeString(mcp, "{\"mcpServers\":{}}");
        var cli = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI);
        writeTrust(workspace, cli.fingerprint());
        var beforeBytes = Files.readAllBytes(trustFile());
        var beforeMtime = Files.getLastModifiedTime(trustFile());

        var migrated = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.DESKTOP_MCP);
        assertEquals(WorkspaceTrust.State.TRUSTED, migrated.state());
        assertEquals("migrated_from_cli_record", migrated.reason());
        assertTrue(migrated.migratedFromLegacy());
        assertNotNull(migrated.migration());
        assertEquals(WorkspaceTrust.canonicalKey(workspace), migrated.migration().legacyKey());
        assertEquals(cli.fingerprint(), migrated.migration().legacyFingerprint());
        assertEquals(WorkspaceTrust.canonicalKey(workspace) + "#desktop-mcp", migrated.migration().desktopKey());
        assertEquals(migrated.fingerprint(), migrated.migration().desktopFingerprint());
        assertArrayEquals(beforeBytes, Files.readAllBytes(trustFile()));
        assertEquals(beforeMtime, Files.getLastModifiedTime(trustFile()));

        var v2Json = """
                {
                  "schemaVersion": 2,
                  "workspaces": {
                    "%s": {"fingerprint": "%s", "trustedAt": "2026-11-17T00:00:00Z"},
                    "%s": {"fingerprint": "%s", "trustedAt": "2026-11-27T00:00:00Z"}
                  }
                }
                """.formatted(
                WorkspaceTrust.canonicalKey(workspace).replace("\\", "\\\\"), cli.fingerprint(),
                migrated.key().replace("\\", "\\\\"), migrated.fingerprint());
        Files.writeString(trustFile(), v2Json, StandardCharsets.UTF_8);

        var afterDesktopWrite = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.DESKTOP_MCP);
        assertEquals(WorkspaceTrust.State.TRUSTED, afterDesktopWrite.state());
        assertNull(afterDesktopWrite.reason());
        assertFalse(afterDesktopWrite.migratedFromLegacy());
        assertNull(afterDesktopWrite.migration());
    }

    @Test
    void revokedTombstoneBlocksLegacyMigrationAndOverridesEmptyWorkspace() throws IOException {
        var workspace = workspace();
        var hooks = workspace.resolve(".core-ai/hooks.json");
        var mcp = workspace.resolve(".core-ai/MCP.json");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "{\"hooks\":{}}");
        Files.writeString(mcp, "{\"mcpServers\":{}}");
        var cli = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI);
        var desktopKey = WorkspaceTrust.canonicalKey(workspace) + "#desktop-mcp";
        Files.createDirectories(trustFile().getParent());
        var v2Json = """
                {
                  "schemaVersion": 2,
                  "workspaces": {
                    "%s": {"fingerprint": "%s", "trustedAt": "2026-11-17T00:00:00Z"},
                    "%s": {"state": "revoked", "revokedAt": "2026-11-27T00:00:00Z"}
                  }
                }
                """.formatted(
                WorkspaceTrust.canonicalKey(workspace).replace("\\", "\\\\"), cli.fingerprint(),
                desktopKey.replace("\\", "\\\\"));
        Files.writeString(trustFile(), v2Json, StandardCharsets.UTF_8);
        var beforeBytes = Files.readAllBytes(trustFile());

        var revoked = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.DESKTOP_MCP);
        assertEquals(WorkspaceTrust.State.RESTRICTED, revoked.state());
        assertEquals("trust_revoked", revoked.reason());
        assertFalse(revoked.migratedFromLegacy());
        assertNull(revoked.migration());
        assertArrayEquals(beforeBytes, Files.readAllBytes(trustFile()));

        var cliEval = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.CLI);
        assertEquals(WorkspaceTrust.State.TRUSTED, cliEval.state());

        Files.delete(hooks);
        Files.delete(mcp);
        var revokedEmpty = WorkspaceTrust.evaluate(workspace, trustFile(), WorkspaceTrust.Mode.DESKTOP_MCP);
        assertEquals(WorkspaceTrust.State.RESTRICTED, revokedEmpty.state());
        assertEquals("trust_revoked", revokedEmpty.reason());
        assertFalse(revokedEmpty.hasExecutableInputs());
    }
}
