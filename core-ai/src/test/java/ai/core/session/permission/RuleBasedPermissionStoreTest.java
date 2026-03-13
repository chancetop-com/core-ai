package ai.core.session.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedPermissionStoreTest {
    private RuleBasedPermissionStore store;

    @BeforeEach
    void setUp() {
        store = new RuleBasedPermissionStore();
    }

    @Test
    void isApprovedReturnsFalseByDefault() {
        assertFalse(store.isApproved("read_file"));
    }

    @Test
    void approveAddsAllowRule() {
        store.approve("read_file");
        assertTrue(store.isApproved("read_file"));
        assertFalse(store.isApproved("write_file"));
    }

    @Test
    void approvedToolsReturnsAllAllowedTools() {
        store.approve("read_file");
        store.approve("grep_file");
        var tools = store.approvedTools();
        assertTrue(tools.contains("read_file"));
        assertTrue(tools.contains("grep_file"));
        assertEquals(2, tools.size());
    }

    @Test
    void matchRuleReturnsHighestPriority() {
        store.addRule(new PermissionRule("*", null, PermissionLevel.DENY, PermissionScope.PERSISTENT, 0));
        store.addRule(new PermissionRule("read_file", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 10));

        var result = store.matchRule("read_file", Map.of());
        assertTrue(result.isPresent());
        assertEquals(PermissionLevel.ALLOW, result.get().getLevel());
    }

    @Test
    void matchRuleWithPathPattern() {
        store.addRule(new PermissionRule("read_file", "/Users/lim/workspace/**", PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0));
        store.addRule(new PermissionRule("read_file", "/etc/passwd", PermissionLevel.DENY, PermissionScope.PERSISTENT, 10));

        var allowResult = store.matchRule("read_file", Map.of("file_path", "/Users/lim/workspace/a.txt"));
        assertTrue(allowResult.isPresent());
        assertEquals(PermissionLevel.ALLOW, allowResult.get().getLevel());

        var denyResult = store.matchRule("read_file", Map.of("file_path", "/etc/passwd"));
        assertTrue(denyResult.isPresent());
        assertEquals(PermissionLevel.DENY, denyResult.get().getLevel());
    }

    @Test
    void sessionRulesAreConsidered() {
        store.addRule(new PermissionRule("shell_command", null, PermissionLevel.ALLOW, PermissionScope.SESSION, 5));

        var result = store.matchRule("shell_command", Map.of());
        assertTrue(result.isPresent());
        assertEquals(PermissionLevel.ALLOW, result.get().getLevel());
    }

    @Test
    void clearSessionRulesOnlyRemovesSessionRules() {
        store.addRule(new PermissionRule("read_file", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0));
        store.addRule(new PermissionRule("shell_command", null, PermissionLevel.ALLOW, PermissionScope.SESSION, 0));

        store.clearSessionRules();

        assertTrue(store.isApproved("read_file"));
        assertFalse(store.matchRule("shell_command", Map.of()).isPresent());
    }

    @Test
    void removeRuleRemovesMatchingRule() {
        store.addRule(new PermissionRule("read_file", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0));
        assertTrue(store.isApproved("read_file"));

        store.removeRule("read_file", null);
        assertFalse(store.isApproved("read_file"));
    }

    @Test
    void addRuleReplacesExistingWithSameToolAndPath() {
        store.addRule(new PermissionRule("read_file", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0));
        assertTrue(store.isApproved("read_file"));

        store.addRule(new PermissionRule("read_file", null, PermissionLevel.DENY, PermissionScope.PERSISTENT, 0));
        assertFalse(store.isApproved("read_file"));

        var result = store.matchRule("read_file", Map.of());
        assertTrue(result.isPresent());
        assertEquals(PermissionLevel.DENY, result.get().getLevel());
    }

    @Test
    void noMatchReturnsEmpty() {
        var result = store.matchRule("unknown_tool", Map.of());
        assertFalse(result.isPresent());
    }

    @Test
    void getRulesReturnsAllRules() {
        store.addRule(new PermissionRule("a", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 0));
        store.addRule(new PermissionRule("b", null, PermissionLevel.DENY, PermissionScope.SESSION, 0));
        assertEquals(2, store.getRules().size());
    }

    @Test
    void wildcardDenyWithSpecificAllow() {
        store.addRule(new PermissionRule("*", null, PermissionLevel.DENY, PermissionScope.PERSISTENT, 0));
        store.addRule(new PermissionRule("read_file", null, PermissionLevel.ALLOW, PermissionScope.PERSISTENT, 10));

        assertFalse(store.isApproved("shell_command"));
        assertTrue(store.isApproved("read_file"));
    }
}
