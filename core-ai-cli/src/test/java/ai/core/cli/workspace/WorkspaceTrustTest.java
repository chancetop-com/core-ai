package ai.core.cli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        var evaluation = WorkspaceTrust.evaluate(workspace(), trustFile());

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

        var untrusted = WorkspaceTrust.evaluate(workspace, trustFile());
        assertEquals(WorkspaceTrust.State.RESTRICTED, untrusted.state());
        assertEquals("not_trusted", untrusted.reason());
        assertTrue(untrusted.hasExecutableInputs());

        writeTrust(workspace, untrusted.fingerprint());
        var trusted = WorkspaceTrust.evaluate(workspace, trustFile());
        assertEquals(WorkspaceTrust.State.TRUSTED, trusted.state());
        assertEquals(untrusted.fingerprint(), trusted.fingerprint());
    }

    @Test
    void changingAnExecutableInputInvalidatesTheRecord() throws IOException {
        var workspace = workspace();
        var hooks = workspace.resolve(".core-ai/hooks.json");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "{\"hooks\":{\"SessionStart\":[]}}");
        writeTrust(workspace, WorkspaceTrust.evaluate(workspace, trustFile()).fingerprint());

        Files.writeString(hooks, "{\"hooks\":{\"SessionStart\":[{\"command\":\"echo hi\"}]}}");

        var evaluation = WorkspaceTrust.evaluate(workspace, trustFile());
        assertEquals(WorkspaceTrust.State.RESTRICTED, evaluation.state());
        assertEquals("fingerprint_changed", evaluation.reason());
    }

    @Test
    void workspaceSkillsAndPluginsAreTrustInputs() throws IOException {
        var workspace = workspace();
        var skill = workspace.resolve(".core-ai/skills/demo/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "# demo");

        assertTrue(WorkspaceTrust.evaluate(workspace, trustFile()).hasExecutableInputs());

        var legacyPlugin = workspace.resolve(".core-ai/plugins/demo/hooks/hooks.json");
        Files.createDirectories(legacyPlugin.getParent());
        Files.writeString(legacyPlugin, "{}");
        var fingerprint = WorkspaceTrust.evaluate(workspace, trustFile()).fingerprint();
        writeTrust(workspace, fingerprint);
        assertEquals(WorkspaceTrust.State.TRUSTED, WorkspaceTrust.evaluate(workspace, trustFile()).state());

        Files.writeString(legacyPlugin, "{\"hooks\":{}}");
        assertEquals("fingerprint_changed", WorkspaceTrust.evaluate(workspace, trustFile()).reason());
    }

    @Test
    void aRecordForAnotherWorkspaceKeyDoesNotTrustThisOne() throws IOException {
        var workspace = workspace();
        var hooks = workspace.resolve(".core-ai/agent.properties");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "agent.max.turn=5");

        writeTrust(temp.resolve("elsewhere"), WorkspaceTrust.evaluate(workspace, trustFile()).fingerprint());

        assertEquals(WorkspaceTrust.State.RESTRICTED, WorkspaceTrust.evaluate(workspace, trustFile()).state());
    }
}
