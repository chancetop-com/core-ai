package ai.core.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class SkillConfigTest {

    @Test
    void ofCreatesConfigFromPaths() {
        var config = SkillConfig.of("/path/a", "/path/b");
        assertTrue(config.isEnabled());
        assertEquals(2, config.getSources().size());
        assertEquals("/path/a", config.getSources().get(0).path());
        assertEquals("/path/b", config.getSources().get(1).path());
        assertEquals(0, config.getSources().get(0).priority());
        assertEquals(1, config.getSources().get(1).priority());
    }

    @Test
    void disabledCreatesDisabledConfig() {
        var config = SkillConfig.disabled();
        assertFalse(config.isEnabled());
    }

    @Test
    void builderSortsByPriority() {
        var config = SkillConfig.builder()
                .source("high", "/path/high", 10)
                .source("low", "/path/low", 1)
                .source("mid", "/path/mid", 5)
                .build();

        assertEquals(3, config.getSources().size());
        assertEquals("low", config.getSources().get(0).name());
        assertEquals("mid", config.getSources().get(1).name());
        assertEquals("high", config.getSources().get(2).name());
    }

    @Test
    void builderDefaultValues() {
        var config = SkillConfig.builder().build();
        assertTrue(config.isEnabled());
        assertTrue(config.getSources().isEmpty());
        assertEquals(10 * 1024 * 1024, config.getMaxSkillFileSize());
    }

    @Test
    void builderCustomMaxFileSize() {
        var config = SkillConfig.builder()
                .maxSkillFileSize(5 * 1024 * 1024)
                .build();
        assertEquals(5 * 1024 * 1024, config.getMaxSkillFileSize());
    }

    @Test
    void workspaceAddsCoreDotAiSkillsSource() {
        String cwd = System.getProperty("user.dir");
        var config = SkillConfig.builder()
                .source("built-in", "/built-in/skills", 0)
                .workspace()
                .build();

        assertEquals(2, config.getSources().size());
        var workspaceSource = config.getSources().stream()
                .filter(s -> "workspace".equals(s.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(workspaceSource);
        assertEquals(cwd + "/.core-ai/skills", workspaceSource.path());
        assertEquals(100, workspaceSource.priority());
    }
}
