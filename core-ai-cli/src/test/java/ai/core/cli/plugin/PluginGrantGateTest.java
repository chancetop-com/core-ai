package ai.core.cli.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginGrantGateTest {

    private static final String DIGEST = "b".repeat(64);
    private static final String PLUGIN_ID = "example.hello-plugin";

    @TempDir
    Path temp;

    private Path skillDir() throws IOException {
        var dir = temp.resolve("packages").resolve(DIGEST).resolve("skills");
        Files.createDirectories(dir);
        return dir;
    }

    private void state(String granted, boolean enabled, String digest, long grantEpoch) throws IOException {
        var json = """
                {
                  "schemaVersion": 1,
                  "revision": 3,
                  "grantEpoch": %d,
                  "packages": {
                    "%s": {"pluginId": "%s", "requiredCapabilities": ["engine.skills", "ui.dock"]}
                  },
                  "installs": [
                    {"scope": "user", "pluginId": "%s", "digest": "%s", "enabled": %s, "granted": [%s]}
                  ]
                }
                """.formatted(grantEpoch, digest, PLUGIN_ID, PLUGIN_ID, digest, enabled, granted);
        Files.writeString(temp.resolve("state.json"), json);
    }

    private PluginGrantGate gate(Path skills, long epoch) {
        return PluginGrantGate.of(List.of(new PluginGrantGate.Root(PLUGIN_ID, skills.toString(), DIGEST, epoch)),
                temp.resolve("state.json"));
    }

    @Test
    void permitsAnActiveGrantAndNonPluginSkills() throws IOException {
        var skills = skillDir();
        state("\"engine.skills\",\"ui.dock\"", true, DIGEST, 2);

        var gate = gate(skills, 1);

        assertTrue(gate.permits(skills.resolve("hello-plugin").toString()));
        assertTrue(gate.permits(temp.resolve("workspace-skills/other").toString()));
        assertTrue(gate.permits(null));
    }

    @Test
    void deniesAfterRevokeDisableDigestChangeOrRollback() throws IOException {
        var skills = skillDir();
        var skill = skills.resolve("hello-plugin").toString();

        state("\"engine.skills\",\"ui.dock\"", true, DIGEST, 2);
        assertTrue(gate(skills, 1).permits(skill));

        state("", true, DIGEST, 3);
        assertFalse(gate(skills, 1).permits(skill), "revoked grant must deny the next call");

        state("\"engine.skills\",\"ui.dock\"", false, DIGEST, 4);
        assertFalse(gate(skills, 1).permits(skill), "disabled plugin must deny");

        state("\"engine.skills\",\"ui.dock\"", true, "c".repeat(64), 5);
        assertFalse(gate(skills, 1).permits(skill), "digest change must deny");

        state("\"engine.skills\",\"ui.dock\"", true, DIGEST, 1);
        assertFalse(gate(skills, 2).permits(skill), "epoch rollback must deny");
    }

    @Test
    void deniesWhenARequiredCapabilityIsMissingOrTheStateIsUnreadable() throws IOException {
        var skills = skillDir();
        var skill = skills.resolve("hello-plugin").toString();

        state("\"engine.skills\"", true, DIGEST, 2);
        assertFalse(gate(skills, 1).permits(skill));

        Files.writeString(temp.resolve("state.json"), "{not json");
        assertFalse(gate(skills, 1).permits(skill));

        Files.delete(temp.resolve("state.json"));
        assertFalse(gate(skills, 1).permits(skill));
    }

    @Test
    void anEmptyGateAllowsNormalSkills() {
        assertTrue(PluginGrantGate.of(List.of(), null).permits("/anywhere"));
    }

    @Test
    void olderStateWithoutRequirementsFallsBackToThePackageManifest() throws IOException {
        var skills = skillDir();
        var manifest = temp.resolve("packages").resolve(DIGEST).resolve(".claude-plugin").resolve("plugin.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {"name": "x", "version": "1.0.0",
                 "coreai": {"schemaVersion": 1, "id": "%s",
                            "capabilities": {"required": ["engine.skills", "ui.dock"]}}}
                """.formatted(PLUGIN_ID));
        var json = """
                {
                  "schemaVersion": 1, "grantEpoch": 2,
                  "packages": { "%s": {"pluginId": "%s"} },
                  "installs": [
                    {"scope": "user", "pluginId": "%s", "digest": "%s", "enabled": true, "granted": ["engine.skills"]}
                  ]
                }
                """.formatted(DIGEST, PLUGIN_ID, PLUGIN_ID, DIGEST);
        Files.writeString(temp.resolve("state.json"), json);

        assertFalse(gate(skills, 1).permits(skills.resolve("hello-plugin").toString()));
    }
}
