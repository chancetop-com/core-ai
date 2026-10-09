package ai.core.cli.appserver;

import ai.core.session.FileRuleBasedPermissionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class WorkspaceAutoPermissionStoreTest {
    @TempDir
    Path workspace;

    @Test
    void allowsRelativeWriteInsideWorkspace() {
        var store = storeWithFreshRules();
        assertEquals(Boolean.TRUE, store.checkPermission("write_file", Map.of("file_path", "src/App.java")).orElseThrow());
    }

    @Test
    void allowsAbsoluteWriteInsideWorkspace() {
        var store = storeWithFreshRules();
        var path = workspace.resolve("docs/readme.md").toString();
        assertEquals(Boolean.TRUE, store.checkPermission("edit_file", Map.of("file_path", path)).orElseThrow());
    }

    @Test
    void promptsForWriteOutsideWorkspace() {
        var store = storeWithFreshRules();
        var parent = workspace.getParent();
        assertNotNull(parent);
        var outside = parent.resolve("outside.txt").toString();
        assertTrue(store.checkPermission("write_file", Map.of("file_path", outside)).isEmpty());
    }

    @Test
    void dotDotEscapeIsNotInsideWorkspace() {
        var store = storeWithFreshRules();
        assertTrue(store.checkPermission("write_file", Map.of("file_path", "../escape.txt")).isEmpty());
    }

    @Test
    void explicitDenyWinsOverWorkspaceAllow() {
        var delegate = new FileRuleBasedPermissionStore(workspace.resolve("rules.json"));
        delegate.deny("write_file(secret.txt)");
        var store = new WorkspaceAutoPermissionStore(delegate, workspace);
        assertEquals(Boolean.FALSE, store.checkPermission("write_file", Map.of("file_path", "secret.txt")).orElseThrow());
    }

    @Test
    void explicitAllowOutsideWorkspaceStillApplies() {
        var delegate = new FileRuleBasedPermissionStore(workspace.resolve("rules.json"));
        delegate.allow("edit_file(*)");
        var store = new WorkspaceAutoPermissionStore(delegate, workspace);
        var parent = workspace.getParent();
        assertNotNull(parent);
        var outside = parent.resolve("other.txt").toString();
        assertEquals(Boolean.TRUE, store.checkPermission("edit_file", Map.of("file_path", outside)).orElseThrow());
    }

    @Test
    void nonWriteToolsAreNotAutoApproved() {
        var store = storeWithFreshRules();
        assertTrue(store.checkPermission("read_file", Map.of("file_path", "src/App.java")).isEmpty());
    }

    private WorkspaceAutoPermissionStore storeWithFreshRules() {
        return new WorkspaceAutoPermissionStore(new FileRuleBasedPermissionStore(workspace.resolve("rules.json")), workspace);
    }
}
