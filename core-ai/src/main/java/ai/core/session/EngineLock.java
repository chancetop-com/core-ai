package ai.core.session;

import java.nio.file.Path;

/**
 * One engine per workspace: the engine writes the sidecars, so a second engine must fail fast
 * instead of racing the first one.
 *
 * @author stephen
 */
public final class EngineLock {
    public static final String ID = "engine";

    public static SessionLock.AcquireResult tryAcquire(Path sessionsDir, String process) {
        return SessionLock.tryAcquire(sessionsDir, ID, process);
    }

    private EngineLock() {
    }
}
