package ai.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class FilesystemSkillProviderTest {

    @Test
    void resolvesPluginRootPathFromSiblingRoot(@TempDir Path root) throws IOException {
        var adsTiktok = root.resolve("AgriciDaniel").resolve("ads-tiktok");
        Files.createDirectories(adsTiktok);
        Files.writeString(adsTiktok.resolve("SKILL.md"), """
            ---
            name: ads-tiktok
            description: Audit TikTok Ads
            ---

            Read `ads/references/tiktok-audit.md`.
            """);
        var ads = root.resolve("AgriciDaniel").resolve("ads");
        Files.createDirectories(ads.resolve("references"));
        Files.writeString(ads.resolve("SKILL.md"), """
            ---
            name: ads
            description: Operate paid advertising
            ---
            """);
        Files.writeString(ads.resolve("references").resolve("tiktok-audit.md"), "# TikTok audit");

        var provider = new FilesystemSkillProvider("local", root.toString(), 1);
        var skill = provider.listSkills().stream()
            .filter(candidate -> "ads-tiktok".equals(candidate.getName()))
            .findFirst().orElseThrow();

        assertEquals("# TikTok audit", provider.readResource(skill, "ads/references/tiktok-audit.md"));
    }

    @Test
    void doesNotReadAboveTheProviderRoot(@TempDir Path base) throws IOException {
        var root = base.resolve("skills");
        var skillDir = root.resolve("ads-tiktok");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: ads-tiktok
            description: Audit TikTok Ads
            ---
            """);
        Files.writeString(base.resolve("secret.txt"), "secret");

        var provider = new FilesystemSkillProvider("local", root.toString(), 1);
        var skill = provider.listSkills().getFirst();

        var error = assertThrows(SkillLoadException.class, () -> provider.readResource(skill, "secret.txt"));
        assertTrue(error.getMessage().contains("failed to read resource"));
    }
}
