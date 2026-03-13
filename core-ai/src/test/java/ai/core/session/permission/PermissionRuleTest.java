package ai.core.session.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRuleTest {

    @Test
    void matchesExactToolName() {
        var rule = new PermissionRule("read_file", null);
        assertTrue(rule.matchesToolName("read_file"));
        assertFalse(rule.matchesToolName("write_file"));
    }

    @Test
    void wildcardMatchesAnyTool() {
        var rule = new PermissionRule("*", null);
        assertTrue(rule.matchesToolName("read_file"));
        assertTrue(rule.matchesToolName("shell_command"));
    }

    @Test
    void nullPathPatternMatchesAnyPath() {
        var rule = new PermissionRule("read_file", null);
        assertTrue(rule.matchesPath("/any/path"));
        assertTrue(rule.matchesPath(null));
    }

    @Test
    void doubleStarPathPatternMatchesRecursively() {
        var rule = new PermissionRule("read_file", "/home/user/workspace/**");
        assertTrue(rule.matchesPath("/home/user/workspace/file.txt"));
        assertTrue(rule.matchesPath("/home/user/workspace/deep/nested/file.txt"));
        assertFalse(rule.matchesPath("/etc/passwd"));
    }

    @Test
    void exactPathPatternMatchesExactly() {
        var rule = new PermissionRule("read_file", "/etc/passwd");
        assertTrue(rule.matchesPath("/etc/passwd"));
        assertFalse(rule.matchesPath("/etc/shadow"));
    }

    @Test
    void nullPathAlwaysMatchesWhenPatternExists() {
        var rule = new PermissionRule("read_file", "/some/path/**");
        assertTrue(rule.matchesPath(null));
    }

    @Test
    void matchesCombinesToolAndPath() {
        var rule = new PermissionRule("read_file", "/home/user/**");
        assertTrue(rule.matches("read_file", "/home/user/a.txt"));
        assertFalse(rule.matches("write_file", "/home/user/a.txt"));
        assertFalse(rule.matches("read_file", "/etc/passwd"));
    }
}
