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
        Files.createDirectories(repoDir.resolve("skills"));

        assertEquals(List.of("skills"), detector.detectSkillPaths(repoDir));
    }

    @Test
    void detectsEveryRootDeclaredAsArray(@TempDir Path repoDir) throws IOException {
        // AgriciDaniel/claude-ads layout: a shared reference root next to the platform skills
        Files.createDirectories(repoDir.resolve(".claude-plugin"));
        Files.writeString(repoDir.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\": \"claude-ads\", \"skills\": [\"./ads/\", \"./skills/\"]}");
        Files.createDirectories(repoDir.resolve("ads").resolve("references"));
        Files.writeString(repoDir.resolve("ads").resolve("SKILL.md"),
            "---\nname: ads\ndescription: Operate paid advertising\n---\n");
        Files.writeString(repoDir.resolve("ads").resolve("references").resolve("tiktok-audit.md"), "# TikTok audit");
        Files.createDirectories(repoDir.resolve("skills").resolve("ads-tiktok"));
        Files.writeString(repoDir.resolve("skills").resolve("ads-tiktok").resolve("SKILL.md"),
            "---\nname: ads-tiktok\ndescription: Audit TikTok Ads\n---\n\nRead `ads/references/tiktok-audit.md`.\n");

        var paths = detector.detectSkillPaths(repoDir);
        assertEquals(List.of("ads", "skills"), paths);

        var loader = new SkillLoader(10 * 1024 * 1024);
        var skills = new ArrayList<SkillMetadata>();
        for (var path : paths) {
            skills.addAll(loader.loadFromSource(repoDir.resolve(path).toString()));
        }
        assertEquals(2, skills.size());
        var ads = skills.stream().filter(skill -> "ads".equals(skill.getName())).findFirst().orElseThrow();
        assertTrue(ads.getResources().contains("references/tiktok-audit.md"));
    }

    @Test
    void ignoresDeclaredSkillPathThatDoesNotExist(@TempDir Path repoDir) throws IOException {
        Files.createDirectories(repoDir.resolve(".claude-plugin"));
        Files.writeString(repoDir.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"skills\": [\"./ads/\", \"./skills/\"]}");
        Files.createDirectories(repoDir.resolve("skills"));

        assertEquals(List.of("skills"), detector.detectSkillPaths(repoDir));
    }

    @Test
    void detectsNestedPluginManifestOfMarketplaceSource(@TempDir Path repoDir) throws IOException {
        Files.createDirectories(repoDir.resolve(".claude-plugin"));
        Files.writeString(repoDir.resolve(".claude-plugin").resolve("marketplace.json"),
            "{\"plugins\": [{\"name\": \"demo\", \"source\": \"./mods/demo\"}]}");
        Files.createDirectories(repoDir.resolve("mods").resolve("demo").resolve(".claude-plugin"));
        Files.writeString(repoDir.resolve("mods").resolve("demo").resolve(".claude-plugin").resolve("plugin.json"),
            "{\"skills\": [\"./ads\", \"./skills\"]}");
        Files.createDirectories(repoDir.resolve("mods").resolve("demo").resolve("ads"));
        Files.createDirectories(repoDir.resolve("mods").resolve("demo").resolve("skills"));

        assertEquals(List.of("mods/demo/ads", "mods/demo/skills"), detector.detectSkillPaths(repoDir));
    }

    @Test
    void detectsAllSkillDirsFromRootSourceMarketplace(@TempDir Path repoDir) throws IOException {
        // MiniMax-AI/skills layout: plugin.json without a skills field,
        // marketplace.json pointing to the repo root, skills in both .claude/skills and skills/
        Files.createDirectories(repoDir.resolve(".claude-plugin"));
        Files.writeString(repoDir.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\": \"minimax-skills\", \"version\": \"1.0.0\"}");
        Files.writeString(repoDir.resolve(".claude-plugin").resolve("marketplace.json"),
            "{\"plugins\": [{\"name\": \"minimax-skills\", \"source\": \"./\"}]}");
        Files.createDirectories(repoDir.resolve(".claude").resolve("skills").resolve("pr-review"));
        Files.writeString(repoDir.resolve(".claude").resolve("skills").resolve("pr-review").resolve("SKILL.md"),
            "---\nname: pr-review\ndescription: Review pull requests\n---\n");
        Files.createDirectories(repoDir.resolve("skills").resolve("minimax-pdf"));
        Files.writeString(repoDir.resolve("skills").resolve("minimax-pdf").resolve("SKILL.md"),
            "---\nname: minimax-pdf\ndescription: Generate PDF documents\n---\n");
        Files.createDirectories(repoDir.resolve("skills").resolve("minimax-docx"));
        Files.writeString(repoDir.resolve("skills").resolve("minimax-docx").resolve("SKILL.md"),
            "---\nname: minimax-docx\ndescription: Generate DOCX documents\n---\n");

        var paths = detector.detectSkillPaths(repoDir);
        assertEquals(List.of(".claude/skills", "skills"), paths);

        var loader = new SkillLoader(10 * 1024 * 1024);
        var skills = new ArrayList<SkillMetadata>();
        for (var path : paths) {
            skills.addAll(loader.loadFromSource(repoDir.resolve(path).toString()));
        }
        assertEquals(3, skills.size());
        assertTrue(skills.stream().anyMatch(s -> "minimax-pdf".equals(s.getName())));
        assertTrue(skills.stream().anyMatch(s -> "minimax-docx".equals(s.getName())));
        assertTrue(skills.stream().anyMatch(s -> "pr-review".equals(s.getName())));
    }

    @Test
    void detectsSkillsDirFromRootSourceMarketplace(@TempDir Path repoDir) throws IOException {
        // A root-source marketplace repo whose only skill container is skills/
        Files.createDirectories(repoDir.resolve(".claude-plugin"));
        Files.writeString(repoDir.resolve(".claude-plugin").resolve("marketplace.json"),
            "{\"plugins\": [{\"name\": \"demo\", \"source\": \"./\"}]}");
        Files.createDirectories(repoDir.resolve("skills").resolve("demo-skill"));
        Files.writeString(repoDir.resolve("skills").resolve("demo-skill").resolve("SKILL.md"),
            "---\nname: demo-skill\ndescription: Demo skill\n---\n");

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
