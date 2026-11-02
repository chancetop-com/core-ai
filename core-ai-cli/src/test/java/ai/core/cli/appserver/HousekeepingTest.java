package ai.core.cli.appserver;

import ai.core.session.SessionLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HousekeepingTest {
    @Test
    void removesOnlyStaleTempFiles(@TempDir Path root) throws Exception {
        var sessions = Files.createDirectories(root.resolve("sessions/ws"));
        var stale = sessions.resolve(".old.data.tmp");
        var fresh = sessions.resolve(".new.data.tmp");
        Files.writeString(stale, "x");
        Files.writeString(fresh, "x");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 60 * 60 * 1000));

        Housekeeping.run(sessions);

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void stagingCleanupIsGlobalAndKeepsOwnedOrLiveSessions(@TempDir Path root) throws Exception {
        var sessionsBase = Files.createDirectories(root.resolve("sessions"));
        var workspaceA = Files.createDirectories(sessionsBase.resolve("a"));
        var workspaceB = Files.createDirectories(sessionsBase.resolve("b"));
        var staging = Files.createDirectories(root.resolve("staging"));
        var old = FileTime.fromMillis(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000);

        var orphan = Files.createDirectories(staging.resolve("orphan"));
        var owned = Files.createDirectories(staging.resolve("owned"));
        var live = Files.createDirectories(staging.resolve("live"));
        Files.writeString(workspaceB.resolve("owned.data"), "{}");
        try (var lock = ((SessionLock.AcquireResult.Acquired) SessionLock.tryAcquire(workspaceA, "live", "test")).lock()) {
            assertNotNull(lock);
            Files.setLastModifiedTime(orphan, old);
            Files.setLastModifiedTime(owned, old);
            Files.setLastModifiedTime(live, old);

            Housekeeping.runEngine(workspaceA, staging, sessionsBase);

            assertFalse(Files.exists(orphan));
            assertTrue(Files.exists(owned), "another workspace must keep its staging directory");
            assertTrue(Files.exists(live), "a live session must keep its staging directory");
        }
    }
}
