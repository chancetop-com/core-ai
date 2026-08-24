package ai.core.cli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedSkillProvisionerTest {

    @TempDir
    Path tempDir;

    @Test
    void isInstalledDetectsRootSkill() throws Exception {
        var skillDir = tempDir.resolve("browser-use");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: browser-use\n---\n");

        assertTrue(ManagedSkillProvisioner.isInstalled(tempDir, "browser-use"));
    }

    @Test
    void isInstalledDetectsNamespacedSkill() throws Exception {
        var skillDir = tempDir.resolve("core-ai").resolve("browser-use");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: browser-use\n---\n");

        assertTrue(ManagedSkillProvisioner.isInstalled(tempDir, "browser-use"));
    }

    @Test
    void isInstalledReturnsFalseWhenMissing() {
        assertFalse(ManagedSkillProvisioner.isInstalled(tempDir, "browser-use"));
    }

    @Test
    void adaptForWindowsAppendsUsageOnWindows() {
        String content = "---\nname: browser-use\n---\n";

        String adapted = ManagedSkillProvisioner.adaptForWindows(content, true);

        assertTrue(adapted.contains("## Windows Usage"));
        assertTrue(adapted.contains("Get-Content bu.py -Raw | browser-use"));
    }

    @Test
    void adaptForWindowsIsIdempotent() {
        String content = "---\nname: browser-use\n---\n";

        String once = ManagedSkillProvisioner.adaptForWindows(content, true);
        String twice = ManagedSkillProvisioner.adaptForWindows(once, true);

        assertEquals(once, twice);
    }

    @Test
    void adaptForWindowsKeepsContentOnNonWindows() {
        String content = "---\nname: browser-use\n---\n";

        assertEquals(content, ManagedSkillProvisioner.adaptForWindows(content, false));
    }
}
