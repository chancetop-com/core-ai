package ai.core.tool.tools;

import ai.core.skill.SkillSource;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class ReadSkillReferenceToolTest {

    @Test
    void readReferenceFileSuccessfully(@TempDir Path tempDir) throws IOException {
        createSkillWithReference(tempDir, "my-skill", "references/guide.md", "# Guide\nSome content");
        var tool = buildTool(tempDir);
        var result = tool.execute(JSON.toJSON(Map.of("skill_name", "my-skill", "file", "references/guide.md")));
        assertTrue(result.isCompleted());
        assertEquals("# Guide\nSome content", result.getResult());
    }

    @Test
    void readScriptFileSuccessfully(@TempDir Path tempDir) throws IOException {
        createSkillWithScript(tempDir, "my-skill", "scripts/run.sh", "#!/bin/sh\necho hello");
        var tool = buildTool(tempDir);
        var result = tool.execute(JSON.toJSON(Map.of("skill_name", "my-skill", "file", "scripts/run.sh")));
        assertTrue(result.isCompleted());
        assertEquals("#!/bin/sh\necho hello", result.getResult());
    }

    @Test
    void failsWhenSkillNotFound(@TempDir Path tempDir) throws IOException {
        createSkillWithReference(tempDir, "my-skill", "references/guide.md", "content");
        var tool = buildTool(tempDir);
        var result = tool.execute(JSON.toJSON(Map.of("skill_name", "unknown", "file", "references/guide.md")));
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("not found"));
    }

    @Test
    void failsWhenReferenceFileNotFound(@TempDir Path tempDir) throws IOException {
        createSkillWithReference(tempDir, "my-skill", "references/guide.md", "content");
        var tool = buildTool(tempDir);
        var result = tool.execute(JSON.toJSON(Map.of("skill_name", "my-skill", "file", "references/missing.md")));
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("not found"));
    }

    @Test
    void failsOnPathTraversal(@TempDir Path tempDir) throws IOException {
        createSkillWithReference(tempDir, "my-skill", "references/guide.md", "content");
        var tool = buildTool(tempDir);
        var result = tool.execute(JSON.toJSON(Map.of("skill_name", "my-skill", "file", "../../etc/passwd")));
        assertTrue(result.isFailed());
    }

    @Test
    void failsWhenSkillNameMissing(@TempDir Path tempDir) throws IOException {
        createSkillWithReference(tempDir, "my-skill", "references/guide.md", "content");
        var tool = buildTool(tempDir);
        var result = tool.execute(JSON.toJSON(Map.of("file", "references/guide.md")));
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("skill_name"));
    }

    @Test
    void failsWhenFileMissing(@TempDir Path tempDir) throws IOException {
        createSkillWithReference(tempDir, "my-skill", "references/guide.md", "content");
        var tool = buildTool(tempDir);
        var result = tool.execute(JSON.toJSON(Map.of("skill_name", "my-skill")));
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("file"));
    }

    @Test
    void descriptionIncludesReferences(@TempDir Path tempDir) throws IOException {
        createSkillWithReferences(tempDir);
        var tool = buildTool(tempDir);
        String desc = tool.getDescription();
        assertTrue(desc.contains("my-skill"));
        assertTrue(desc.contains("references/guide.md"));
        assertTrue(desc.contains("API usage guide"));
    }

    @Test
    void descriptionShowsNoRefsWhenEmpty(@TempDir Path tempDir) throws IOException {
        var skillDir = tempDir.resolve("basic-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: basic-skill
                description: A basic skill
                ---
                # Basic
                """);
        var tool = buildTool(tempDir);
        String desc = tool.getDescription();
        assertTrue(desc.contains("No skill references"));
    }

    private void createSkillWithReference(Path tempDir, String name, String refFile, String refContent) throws IOException {
        var skillDir = tempDir.resolve(name);
        Path refPath = skillDir.resolve(refFile);
        Files.createDirectories(refPath.getParent());
        Files.writeString(refPath, refContent);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Test skill
                references:
                  - file: %s
                    description: Test reference
                ---
                # %s
                """.formatted(name, refFile, name));
    }

    private void createSkillWithScript(Path tempDir, String name, String scriptFile, String scriptContent) throws IOException {
        var skillDir = tempDir.resolve(name);
        Path scriptPath = skillDir.resolve(scriptFile);
        Files.createDirectories(scriptPath.getParent());
        Files.writeString(scriptPath, scriptContent);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Test skill with script
                references:
                  - file: %s
                    description: Test script
                ---
                # %s
                """.formatted(name, scriptFile, name));
    }

    private void createSkillWithReferences(Path tempDir) throws IOException {
        var skillDir = tempDir.resolve("my-skill");
        var refsDir = skillDir.resolve("references");
        Files.createDirectories(refsDir);
        Files.writeString(refsDir.resolve("guide.md"), "# Guide");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                description: Test skill with refs
                references:
                  - file: references/guide.md
                    description: API usage guide
                ---
                # My Skill
                """);
    }

    private ReadSkillReferenceTool buildTool(Path tempDir) {
        return ReadSkillReferenceTool.builder()
                .sources(List.of(new SkillSource("test", tempDir.toString(), 0)))
                .build();
    }
}
