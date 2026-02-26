package ai.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class SkillLoaderTest {
    private SkillLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SkillLoader(10 * 1024 * 1024);
    }

    @Test
    void loadFromSourceWithValidSkills() {
        String skillsDir = getTestResourcePath("skills");
        var skills = loader.loadFromSource(skillsDir);
        var validSkills = skills.stream().filter(s -> "web-research".equals(s.getName()) || "code-review".equals(s.getName())).toList();
        assertEquals(2, validSkills.size());
    }

    @Test
    void loadFromSourceParsesMetadataCorrectly() {
        String skillsDir = getTestResourcePath("skills");
        var skills = loader.loadFromSource(skillsDir);
        var webResearch = skills.stream().filter(s -> "web-research".equals(s.getName())).findFirst().orElse(null);
        assertNotNull(webResearch);
        assertEquals("Provides structured web research methodology with multi-source collection and synthesis", webResearch.getDescription());
        assertEquals("MIT", webResearch.getLicense());
        assertEquals("Requires web_search and write_file tools", webResearch.getCompatibility());
        assertNotNull(webResearch.getMetadata());
        assertEquals("core-ai-team", webResearch.getMetadata().get("author"));
        assertEquals("1.0", webResearch.getMetadata().get("version"));
        assertNotNull(webResearch.getAllowedTools());
        assertEquals(3, webResearch.getAllowedTools().size());
        assertTrue(webResearch.getAllowedTools().contains("ShellCommandTool"));
    }

    @Test
    void loadFromSourceSkipsInvalidNameSkill() {
        String skillsDir = getTestResourcePath("skills");
        var skills = loader.loadFromSource(skillsDir);
        var invalid = skills.stream().filter(s -> "wrong-name-here".equals(s.getName())).findFirst().orElse(null);
        assertNull(invalid);
    }

    @Test
    void loadFromSourceSkipsNoFrontmatterSkill() {
        String skillsDir = getTestResourcePath("skills");
        var skills = loader.loadFromSource(skillsDir);
        var noFrontmatter = skills.stream().filter(s -> "no-frontmatter".equals(s.getName())).findFirst().orElse(null);
        assertNull(noFrontmatter);
    }

    @Test
    void loadFromSourceSkipsMissingFieldsSkill() {
        String skillsDir = getTestResourcePath("skills");
        var skills = loader.loadFromSource(skillsDir);
        var missingFields = skills.stream().filter(s -> "missing-fields".equals(s.getName())).findFirst().orElse(null);
        assertNull(missingFields);
    }

    @Test
    void loadFromSourceReturnsEmptyForNonExistentDir() {
        var skills = loader.loadFromSource("/non/existent/path");
        assertTrue(skills.isEmpty());
    }

    @Test
    void loadAllMergesWithPriorityOverride(@TempDir Path tempDir) throws IOException {
        var source1 = tempDir.resolve("source1/web-research");
        Files.createDirectories(source1);
        Files.writeString(source1.resolve("SKILL.md"), """
                ---
                name: web-research
                description: Low priority version
                ---
                # Low priority
                """);

        var source2 = tempDir.resolve("source2/web-research");
        Files.createDirectories(source2);
        Files.writeString(source2.resolve("SKILL.md"), """
                ---
                name: web-research
                description: High priority version
                ---
                # High priority
                """);

        var sources = List.of(
                new SkillSource("low", tempDir.resolve("source1").toString(), 0),
                new SkillSource("high", tempDir.resolve("source2").toString(), 1));

        var skills = loader.loadAll(sources);
        assertEquals(1, skills.size());
        assertEquals("High priority version", skills.getFirst().getDescription());
    }

    @Test
    void loadAllReturnsEmptyForNullSources() {
        assertTrue(loader.loadAll(null).isEmpty());
        assertTrue(loader.loadAll(List.of()).isEmpty());
    }

    @Test
    void loadSkillFileSkipsOversizedFiles(@TempDir Path tempDir) throws IOException {
        var smallLoader = new SkillLoader(50);
        var skillDir = tempDir.resolve("big-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: big-skill
                description: This description is intentionally made very long to exceed the maximum file size limit that has been set very low for testing purposes
                ---
                # Big skill with lots of content that exceeds the limit
                """);

        var skills = smallLoader.loadFromSource(tempDir.toString());
        assertTrue(skills.isEmpty());
    }

    @Test
    void validateSkillNameValid() {
        assertTrue(loader.validateSkillName("web-research", "web-research"));
        assertTrue(loader.validateSkillName("code-review", "code-review"));
        assertTrue(loader.validateSkillName("a", "a"));
        assertTrue(loader.validateSkillName("arxiv-search-v2", "arxiv-search-v2"));
    }

    @Test
    void validateSkillNameInvalid() {
        assertFalse(loader.validateSkillName(null, "test"));
        assertFalse(loader.validateSkillName("", ""));
        assertFalse(loader.validateSkillName("-bad-name", "-bad-name"));
        assertFalse(loader.validateSkillName("bad-name-", "bad-name-"));
        assertFalse(loader.validateSkillName("web--research", "web--research"));
        assertFalse(loader.validateSkillName("Web-Research", "Web-Research"));
        assertFalse(loader.validateSkillName("web_research", "web_research"));
        assertFalse(loader.validateSkillName("name-mismatch", "different-dir"));
        var longName = "a".repeat(65);
        assertFalse(loader.validateSkillName(longName, longName));
    }

    @Test
    void parseSkillMdWithValidContent() {
        String content = """
                ---
                name: test-skill
                description: A test skill
                license: MIT
                ---
                # Test
                """;
        var skill = loader.parseSkillMd(content, "/path/test-skill/SKILL.md", "test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
        assertEquals("A test skill", skill.getDescription());
        assertEquals("MIT", skill.getLicense());
    }

    @Test
    void parseSkillMdReturnsNullForInvalidContent() {
        assertNull(loader.parseSkillMd("no frontmatter here", "/path/SKILL.md", "test"));
        assertNull(loader.parseSkillMd("---\n---\n", "/path/SKILL.md", "test"));
    }

    private String getTestResourcePath(String path) {
        var resource = Thread.currentThread().getContextClassLoader().getResource(path);
        if (resource == null) throw new RuntimeException("Test resource not found: " + path);
        try {
            return Path.of(resource.toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
