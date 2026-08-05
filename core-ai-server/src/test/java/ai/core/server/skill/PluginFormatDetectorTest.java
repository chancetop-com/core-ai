package ai.core.server.skill;

import ai.core.skill.SkillLoader;
import ai.core.skill.SkillMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class PluginFormatDetectorTest {
    private final PluginFormatDetector detector = new PluginFormatDetector();

    @Test
    void detectsRootLevelSkillMd(@TempDir Path repoDir) throws IOException {
        Files.writeString(repoDir.resolve("SKILL.md"), "---\nname: pretty-mermaid\ndescription: Render diagrams\n---\n");

        assertEquals(List.of("."), detector.detectSkillPaths(repoDir));
        assertTrue(detector.hasPluginFormat(repoDir));
    }

    @Test
    void detectsClaudeSkillsConvention(@TempDir Path repoDir) throws IOException {
        Files.createDirectories(repoDir.resolve(".claude").resolve("skills"));

        assertEquals(List.of(".claude/skills"), detector.detectSkillPaths(repoDir));
        assertFalse(detector.hasPluginFormat(repoDir));
    }

    @Test
    void detectsClaudePluginJson(@TempDir Path repoDir) throws IOException {
        Files.createDirectories(repoDir.resolve(".claude-plugin"));
        Files.writeString(repoDir.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"skills\": \"skills\"}");

        assertEquals(List.of("skills"), detector.detectSkillPaths(repoDir));
    }

    @Test
    void returnsEmptyForPlainRepo(@TempDir Path repoDir) {
        assertTrue(detector.detectSkillPaths(repoDir).isEmpty());
        assertFalse(detector.hasPluginFormat(repoDir));
    }

    @Test
    void detectsAndLoadsRootLevelSkillRepo(@TempDir Path repoDir) throws IOException {
        Files.writeString(repoDir.resolve("SKILL.md"), """
                ---
                name: pretty-mermaid
                description: Render mermaid diagrams as SVG or ASCII
                ---
                # Pretty Mermaid
                """);
        Files.createDirectories(repoDir.resolve("scripts"));
        Files.writeString(repoDir.resolve("scripts").resolve("render.mjs"), "// render");
        Files.createDirectories(repoDir.resolve("references"));
        Files.writeString(repoDir.resolve("references").resolve("THEMES.md"), "# Themes");

        var paths = detector.detectSkillPaths(repoDir);
        assertEquals(List.of("."), paths);

        var loader = new SkillLoader(10 * 1024 * 1024);
        var skills = new ArrayList<SkillMetadata>();
        for (var path : paths) {
            skills.addAll(loader.loadFromSource(repoDir.resolve(path).toString()));
        }

        assertEquals(1, skills.size());
        var skill = skills.getFirst();
        assertEquals("pretty-mermaid", skill.getName());
        assertEquals("Render mermaid diagrams as SVG or ASCII", skill.getDescription());
        assertTrue(skill.getResources().contains("scripts/render.mjs"));
        assertTrue(skill.getResources().contains("references/THEMES.md"));
    }
}
