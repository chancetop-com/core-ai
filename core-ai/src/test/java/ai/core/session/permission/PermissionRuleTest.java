package ai.core.session.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRuleTest {

    @Test
    void matchesExactToolName() {
        var rule = new PermissionRule("read_file", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0);
        assertTrue(rule.matchesToolName("read_file"));
        assertFalse(rule.matchesToolName("write_file"));
    }

    @Test
    void wildcardMatchesAnyTool() {
        var rule = new PermissionRule("*", null, PermissionLevel.DENY, PermissionScope.SESSION, 0);
        assertTrue(rule.matchesToolName("read_file"));
        assertTrue(rule.matchesToolName("shell_command"));
    }

    @Test
    void nullPathPatternMatchesAnyPath() {
        var rule = new PermissionRule("read_file", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0);
        assertTrue(rule.matchesPath("/any/path"));
        assertTrue(rule.matchesPath(null));
    }

    @Test
    void doubleStarPathPatternMatchesRecursively() {
        var rule = new PermissionRule("read_file", "/Users/lim/workspace/**", PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0);
        assertTrue(rule.matchesPath("/Users/lim/workspace/file.txt"));
        assertTrue(rule.matchesPath("/Users/lim/workspace/deep/nested/file.txt"));
        assertFalse(rule.matchesPath("/etc/passwd"));
    }

    @Test
    void exactPathPatternMatchesExactly() {
        var rule = new PermissionRule("read_file", "/etc/passwd", PermissionLevel.DENY, PermissionScope.PERSISTENT, 10);
        assertTrue(rule.matchesPath("/etc/passwd"));
        assertFalse(rule.matchesPath("/etc/shadow"));
    }

    @Test
    void nullPathAlwaysMatchesWhenPatternExists() {
        var rule = new PermissionRule("read_file", "/some/path/**", PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0);
        assertTrue(rule.matchesPath(null));
    }
}
