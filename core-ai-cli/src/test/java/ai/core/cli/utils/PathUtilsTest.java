package ai.core.cli.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session directories are keyed by workspace identity: the first claimant keeps the basename,
 * colliding workspaces get a stable hash, and further collisions get a numeric suffix.
 *
 * @author stephen
 */
class PathUtilsTest {
    @Test
    void sessionDirectoryNamesAreStableAndDistinctForDifferentWorkspaces() {
        assertEquals("app", PathUtils.sessionDirName("app", "workspace-a", 0));
        var first = PathUtils.sessionDirName("app", "workspace-a", 1);
        var second = PathUtils.sessionDirName("app", "workspace-b", 1);
        assertNotEquals(first, second, "two workspaces with the same basename must not share a directory");
        assertEquals(first, PathUtils.sessionDirName("app", "workspace-a", 1), "the hash must be stable");
        assertTrue(first.startsWith("app-"), first);
        assertEquals(first + "-2", PathUtils.sessionDirName("app", "workspace-a", 2));
    }
}
