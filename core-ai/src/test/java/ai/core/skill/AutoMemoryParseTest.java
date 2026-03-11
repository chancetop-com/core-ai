package ai.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class AutoMemoryParseTest {

    @Test
    void loadAutoMemoryFromSkillDir() {
        var loader = new SkillLoader(10 * 1024 * 1024);
        var projectRoot = java.nio.file.Path.of(System.getProperty("user.dir")).getParent();
        var skillsDir = projectRoot.resolve(".core-ai/skills").toString();
        var sources = List.of(new SkillSource("workspace", skillsDir, 100));
        var skills = loader.loadAll(sources);

        var autoMemory = skills.stream()
                .filter(s -> "auto-memory".equals(s.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(autoMemory, "auto-memory skill should be found in .core-ai/skills/");
        assertTrue(autoMemory.getDescription().contains("Proactively"));
        assertFalse(autoMemory.getTriggers().isEmpty());
        assertEquals("markdown", autoMemory.getOutputFormat());
        assertNotNull(autoMemory.getSkillDir());
    }
}
