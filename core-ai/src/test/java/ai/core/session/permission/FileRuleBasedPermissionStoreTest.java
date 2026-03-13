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
    void allowToolOnly() {
        store.allow("read_file");
        assertTrue(store.checkPermission("read_file", Map.of()).orElse(false));
        assertTrue(store.checkPermission("read_file", Map.of("file_path", "/any/path")).orElse(false));
        assertTrue(store.checkPermission("write_file", Map.of()).isEmpty());
    }

    @Test
    void allowWithExactPath() {
        store.allow("read_file(/home/user/a.txt)");

        assertTrue(store.checkPermission("read_file", Map.of("file_path", "/home/user/a.txt")).orElse(false));
        assertTrue(store.checkPermission("read_file", Map.of("file_path", "/etc/passwd")).isEmpty());
    }

    @Test
    void allowWithWildcard() {
        store.allow("read_file(/home/user/**)");

        assertTrue(store.checkPermission("read_file", Map.of("file_path", "/home/user/deep/file.txt")).orElse(false));
        assertTrue(store.checkPermission("read_file", Map.of("file_path", "/etc/passwd")).isEmpty());
    }

    @Test
    void allowCommandWildcard() {
        store.allow("run_bash_command(echo *)");

        assertTrue(store.checkPermission("run_bash_command", Map.of("command", "echo hello")).orElse(false));
        assertTrue(store.checkPermission("run_bash_command", Map.of("command", "rm -rf /")).isEmpty());
    }

    @Test
    void denyReturnsFalse() {
        store.deny("shell_command");
        var result = store.checkPermission("shell_command", Map.of());
        assertTrue(result.isPresent());
        assertFalse(result.get());
    }

    @Test
    void denyTakesPrecedenceOverAllow() {
        store.allow("read_file");
        store.deny("read_file(/etc/passwd)");

        assertTrue(store.checkPermission("read_file", Map.of("file_path", "/home/user/a.txt")).orElse(false));

        var denyResult = store.checkPermission("read_file", Map.of("file_path", "/etc/passwd"));
        assertTrue(denyResult.isPresent());
        assertFalse(denyResult.get());
    }

    @Test
    void allowRemovesPreviousDeny() {
        store.deny("read_file");
        assertFalse(store.checkPermission("read_file", Map.of()).orElse(true));

        store.allow("read_file");
        assertTrue(store.checkPermission("read_file", Map.of()).orElse(false));
        assertTrue(store.getDenyPatterns().isEmpty());
    }

    @Test
    void denyRemovesPreviousAllow() {
        store.allow("read_file");
        assertTrue(store.checkPermission("read_file", Map.of()).orElse(false));

        store.deny("read_file");
        assertFalse(store.checkPermission("read_file", Map.of()).orElse(true));
        assertTrue(store.getAllowPatterns().isEmpty());
    }

    @Test
    void duplicatePatternsNotAdded() {
        store.allow("read_file(/home/user/a.txt)");
        store.allow("read_file(/home/user/a.txt)");
        assertEquals(1, store.getAllowPatterns().size());
    }
}
