package ai.core.server.session;

import ai.core.server.sandbox.LazySandbox;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort capture of the sandbox filesystem snapshot before session teardown.
 * Capture failures never block sandbox release.
 */
final class SessionSandboxSnapshotHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionSandboxSnapshotHelper.class);

    static void captureBeforeRelease(SandboxService sandboxService, SandboxSnapshotService snapshotService, String sessionId) {
        if (snapshotService == null || !snapshotService.enabled()) return;
        try {
            var sandbox = sandboxService.getSandbox(sessionId);
            if (!(sandbox instanceof LazySandbox lazy)) return;
            if (!lazy.snapshotDirty()) return;
            var ip = lazy.ip();
            var port = lazy.port();
            if (ip == null || port == 0) return; // sandbox never materialized
            if (!lazy.isDelegateTracked()) {
                LOGGER.info("skip snapshot capture, sandbox already released by ttl cleanup: sessionId={}", sessionId);
                return;
            }
            snapshotService.captureBeforeRelease(sessionId, lazy.userId(), lazy.snapshotEpochForCapture(), ip, port, lazy.image());
        } catch (Exception e) {
            LOGGER.warn("sandbox snapshot capture failed, releasing anyway: sessionId={}", sessionId, e);
        }
    }

    private SessionSandboxSnapshotHelper() {
    }
}
