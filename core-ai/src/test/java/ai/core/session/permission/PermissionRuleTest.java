package ai.core.session.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRuleTest {

    @Test
    void buildPatternWithFilePath() {
        var pattern = PermissionRule.buildPattern("read_file", Map.of("file_path", "/home/user/a.txt"));
        assertEquals("read_file(/home/user/a.txt)", pattern);
    }

    @Test
    void buildPatternWithPath() {
        var pattern = PermissionRule.buildPattern("glob_file", Map.of("path", "/src"));
        assertEquals("glob_file(/src)", pattern);
    }

    @Test
    void buildPatternWithCommand() {
        var pattern = PermissionRule.buildPattern("run_bash_command", Map.of("command", "echo hello"));
        assertEquals("run_bash_command(echo hello)", pattern);
    }

    @Test
    void buildPatternWithAnyArg() {
        var pattern = PermissionRule.buildPattern("grep_file", Map.of("pattern", "*.java"));
        assertEquals("grep_file(*.java)", pattern);
    }

    @Test
    void buildPatternWithNullArgs() {
        var pattern = PermissionRule.buildPattern("shell_command", null);
        assertEquals("shell_command", pattern);
    }

    @Test
    void matchesExactToolOnly() {
        assertTrue(PermissionRule.matches("read_file", "read_file", Map.of()));
        assertFalse(PermissionRule.matches("read_file", "write_file", Map.of()));
    }

    @Test
    void matchesExactToolAndArg() {
        assertTrue(PermissionRule.matches(
                "read_file(/home/user/a.txt)", "read_file",
                Map.of("file_path", "/home/user/a.txt")));

        assertFalse(PermissionRule.matches(
                "read_file(/home/user/a.txt)", "read_file",
                Map.of("file_path", "/etc/passwd")));
    }

    @Test
    void matchesWildcardArg() {
        assertTrue(PermissionRule.matches(
                "read_file(/home/user/*)", "read_file",
                Map.of("file_path", "/home/user/a.txt")));

        assertFalse(PermissionRule.matches(
                "read_file(/home/user/*)", "read_file",
                Map.of("file_path", "/etc/passwd")));
    }

    @Test
    void matchesDoubleStarWildcard() {
        assertTrue(PermissionRule.matches(
                "read_file(/home/user/**)", "read_file",
                Map.of("file_path", "/home/user/deep/nested/file.txt")));

        assertFalse(PermissionRule.matches(
                "read_file(/home/user/**)", "read_file",
                Map.of("file_path", "/etc/passwd")));
    }

    @Test
    void matchesCommandWildcard() {
        assertTrue(PermissionRule.matches(
                "run_bash_command(echo *)", "run_bash_command",
                Map.of("command", "echo hello world")));

        assertFalse(PermissionRule.matches(
                "run_bash_command(echo *)", "run_bash_command",
                Map.of("command", "rm -rf /")));
    }

    @Test
    void matchesToolOnlyPatternMatchesAnyArgs() {
        assertTrue(PermissionRule.matches("shell_command", "shell_command", Map.of("command", "ls")));
        assertTrue(PermissionRule.matches("shell_command", "shell_command", Map.of()));
    }

    @Test
    void wrongToolNeverMatches() {
        assertFalse(PermissionRule.matches("read_file(/a.txt)", "write_file", Map.of("file_path", "/a.txt")));
    }

    @Test
    void extractPrimaryArgUsesFirstValue() {
        var result = PermissionRule.extractPrimaryArg(Map.of("pattern", "*.java"));
        assertTrue(result.isPresent());
        assertEquals("*.java", result.get());
    }

    @Test
    void extractPrimaryArgEmpty() {
        assertFalse(PermissionRule.extractPrimaryArg(Map.of()).isPresent());
        assertFalse(PermissionRule.extractPrimaryArg(null).isPresent());
    }
}
