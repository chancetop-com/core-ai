package ai.core.session.permission;

import ai.core.session.FileRuleBasedPermissionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRuleBasedPermissionStoreTest {
    private FileRuleBasedPermissionStore store;

    @BeforeEach
    void setUp() {
        store = new FileRuleBasedPermissionStore();
    }

    @Test
    void noMatchReturnsEmpty() {
        assertTrue(store.checkPermission("read_file", Map.of()).isEmpty());
    }

    @Test
    void allowReturnsTrue() {
        store.allow("read_file", null);
        var result = store.checkPermission("read_file", Map.of());
        assertTrue(result.isPresent());
        assertTrue(result.get());

        assertTrue(store.checkPermission("write_file", Map.of()).isEmpty());
    }

    @Test
    void denyReturnsFalse() {
        store.deny("shell_command", null);
        var result = store.checkPermission("shell_command", Map.of());
        assertTrue(result.isPresent());
        assertFalse(result.get());
    }

    @Test
    void denyTakesPrecedenceOverAllow() {
        store.allow("read_file", null);
        store.deny("read_file", "/etc/passwd");

        var allowResult = store.checkPermission("read_file", Map.of("file_path", "/Users/lim/a.txt"));
        assertTrue(allowResult.isPresent());
        assertTrue(allowResult.get());

        var denyResult = store.checkPermission("read_file", Map.of("file_path", "/etc/passwd"));
        assertTrue(denyResult.isPresent());
        assertFalse(denyResult.get());
    }

    @Test
    void allowWithPathPattern() {
        store.allow("read_file", "/Users/lim/workspace/**");

        var result = store.checkPermission("read_file", Map.of("file_path", "/Users/lim/workspace/a.txt"));
        assertTrue(result.isPresent());
        assertTrue(result.get());

        assertTrue(store.checkPermission("read_file", Map.of("file_path", "/etc/passwd")).isEmpty());
    }

    @Test
    void wildcardDenyWithSpecificAllow() {
        store.deny("*", null);
        store.allow("read_file", null);

        // deny * matches everything including read_file, deny wins
        var result = store.checkPermission("read_file", Map.of());
        assertTrue(result.isPresent());
        assertFalse(result.get());

        var shellResult = store.checkPermission("shell_command", Map.of());
        assertTrue(shellResult.isPresent());
        assertFalse(shellResult.get());
    }

    @Test
    void allowRemovesPreviousDeny() {
        store.deny("read_file", null);
        assertFalse(store.checkPermission("read_file", Map.of()).orElse(true));

        store.allow("read_file", null);
        assertTrue(store.checkPermission("read_file", Map.of()).orElse(false));
        assertTrue(store.getDenyRules().isEmpty());
    }

    @Test
    void denyRemovesPreviousAllow() {
        store.allow("read_file", null);
        assertTrue(store.checkPermission("read_file", Map.of()).orElse(false));

        store.deny("read_file", null);
        assertFalse(store.checkPermission("read_file", Map.of()).orElse(true));
        assertTrue(store.getAllowRules().isEmpty());
    }

    @Test
    void duplicateRulesNotAdded() {
        store.allow("read_file", null);
        store.allow("read_file", null);
        assertEquals(1, store.getAllowRules().size());
    }
}
