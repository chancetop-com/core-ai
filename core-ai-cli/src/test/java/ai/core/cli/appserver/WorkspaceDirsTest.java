package ai.core.cli.appserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class WorkspaceDirsTest {
    @Test
    void sameBasenameGetsDistinctDirectories(@TempDir Path root) throws Exception {
        var base = root.resolve("sessions");
        var first = Files.createDirectories(root.resolve("a/app"));
        var second = Files.createDirectories(root.resolve("b/app"));
        var firstDir = WorkspaceDirs.ensure(first, base);
        var secondDir = WorkspaceDirs.ensure(second, base);
        assertNotEquals(firstDir, secondDir);
        assertTrue(Files.exists(firstDir.resolve("owner.json")));
        assertTrue(Files.exists(secondDir.resolve("owner.json")));
    }

    @Test
    void movedWorkspaceKeepsItsIdentityAndDirectory(@TempDir Path root) throws Exception {
        var base = root.resolve("sessions");
        var first = Files.createDirectories(root.resolve("a/app"));
        var firstDir = WorkspaceDirs.ensure(first, base);
        Files.writeString(firstDir.resolve("s.data"), "{}");
        var moved = root.resolve("b/app");
        Files.createDirectories(root.resolve("b"));
        Files.move(first, moved);
        var movedDir = WorkspaceDirs.ensure(moved, base);
        assertEquals(firstDir, movedDir);
        assertTrue(Files.exists(movedDir.resolve("s.data")));
    }

    @Test
    void legacyBasenameDirectoryIsAdopted(@TempDir Path root) throws Exception {
        var base = root.resolve("sessions");
        var legacy = Files.createDirectories(base.resolve("app"));
        Files.writeString(legacy.resolve("old.data"), "{}");
        var workspace = Files.createDirectories(root.resolve("work/app"));
        var dir = WorkspaceDirs.ensure(workspace, base);
        assertEquals(legacy, dir);
        assertTrue(Files.exists(dir.resolve("owner.json")));
    }

    @Test
    void copiedWorkspaceGetsANewIdentityInsteadOfSharingHistory(@TempDir Path root) throws Exception {
        var base = root.resolve("sessions");
        var original = Files.createDirectories(root.resolve("original/app"));
        var originalDir = WorkspaceDirs.ensure(original, base);
        Files.writeString(originalDir.resolve("old.data"), "{}");
        var copy = Files.createDirectories(root.resolve("copy/app"));
        Files.createDirectories(copy.resolve(".core-ai"));
        Files.copy(original.resolve(".core-ai/workspace.id"), copy.resolve(".core-ai/workspace.id"));
        var copyDir = WorkspaceDirs.ensure(copy, base);
        assertNotEquals(originalDir, copyDir);
        assertTrue(Files.exists(originalDir.resolve("old.data")));
    }

    @Test
    void corruptRegistryIsRebuiltFromOwnerFiles(@TempDir Path root) throws Exception {
        var base = root.resolve("sessions");
        var workspace = Files.createDirectories(root.resolve("work/app"));
        var dir = WorkspaceDirs.ensure(workspace, base);
        Files.writeString(base.resolve("workspaces.json"), "{not json");
        WorkspaceDirs.clearCache();
        assertEquals(dir, WorkspaceDirs.ensure(workspace, base));
    }
}
