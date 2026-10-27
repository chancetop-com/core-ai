package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * The paths that take a sandbox away: capture a dirty sandbox's /workspace before it goes, and shut the
 * service down. Kept out of {@link SandboxService} because every release path shares the same rules and
 * the service itself is at its size budget.
 *
 * @author stephen
 */
final class SandboxReleaseSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxReleaseSupport.class);

    /**
     * Best-effort snapshot capture before a session's sandbox is released. Used by both the idle-session
     * close path and the sandbox TTL cleanup path; capture failures never block release.
     */
    static void capture(SandboxService service, String sessionId, String sandboxId) {
        var snapshotService = service.snapshotService;
        if (snapshotService == null || !snapshotService.enabled()) return;
        try {
            var sandbox = service.sessionSandboxes.get(sessionId);
            if (!(sandbox instanceof LazySandbox lazy)) return;
            if (sandboxId != null && !sandboxId.equals(lazy.getId())) return;
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

    static void close(SandboxService service, Sandbox sandbox) {
        try {
            if (sandbox instanceof LazySandbox) {
                sandbox.close();
            } else {
                service.sandboxManager().release(sandbox);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release replaced sandbox: id={}", sandbox.getId(), e);
        }
    }

    static void shutdown(SandboxService service) {
        if (!service.enabled) return;
        LOGGER.info("shutting down sandbox service");

        for (var entry : service.sessionSandboxes.entrySet()) {
            if (service.persistentSessionIds.contains(entry.getKey())) continue;
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.warn("failed to close sandbox for session: {}", entry.getKey(), e);
            }
        }
        service.sessionSandboxes.clear();
        service.persistentSessionIds.clear();
        service.sandboxFileService.clearAll();
        service.sessionMcpProcesses.closeAll();
        service.discoverySandbox.close();
        service.cleanupScheduler.shutdown();
        try {
            if (!service.cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                service.cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            service.cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("sandbox service shutdown complete");
    }

    private SandboxReleaseSupport() {
    }
}
