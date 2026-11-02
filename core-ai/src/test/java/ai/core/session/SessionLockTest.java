package ai.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SessionLockTest {
    @Test
    void secondAcquireIsBusyAndReleaseLeavesTheLockFile(@TempDir Path dir) throws Exception {
        var first = SessionLock.tryAcquire(dir, "s1", "test");
        assertInstanceOf(SessionLock.AcquireResult.Acquired.class, first);
        var firstLock = ((SessionLock.AcquireResult.Acquired) first).lock();
        assertTrue(SessionLock.isHeld(dir, "s1"));

        var second = SessionLock.tryAcquire(dir, "s1", "test");
        var busy = assertInstanceOf(SessionLock.AcquireResult.Busy.class, second);
        assertTrue(busy.local());
        assertEquals("test", busy.holder().process());

        firstLock.close();
        assertFalse(SessionLock.isHeld(dir, "s1"));
        assertTrue(Files.exists(dir.resolve("s1.lock")), "release must not delete the lock file");

        var third = SessionLock.tryAcquire(dir, "s1", "test");
        assertInstanceOf(SessionLock.AcquireResult.Acquired.class, third);
        ((SessionLock.AcquireResult.Acquired) third).lock().close();
    }

    @Test
    void engineLockUsesTheEngineName(@TempDir Path dir) {
        var first = EngineLock.tryAcquire(dir, "engine");
        assertInstanceOf(SessionLock.AcquireResult.Acquired.class, first);
        try (var lock = ((SessionLock.AcquireResult.Acquired) first).lock()) {
            assertNotNull(lock);
            assertInstanceOf(SessionLock.AcquireResult.Busy.class, EngineLock.tryAcquire(dir, "engine"));
        }
    }
}
