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
        assertEquals("/path/a", config.getSources().get(0).getPath());
        assertEquals("/path/b", config.getSources().get(1).getPath());
        assertEquals(0, config.getSources().get(0).getPriority());
        assertEquals(1, config.getSources().get(1).getPriority());
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
        assertEquals("low", config.getSources().get(0).getName());
        assertEquals("mid", config.getSources().get(1).getName());
        assertEquals("high", config.getSources().get(2).getName());
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
                .filter(s -> "workspace".equals(s.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(workspaceSource);
        assertEquals(cwd + "/.core-ai/skills", workspaceSource.getPath());
        assertEquals(100, workspaceSource.getPriority());
    }
}
