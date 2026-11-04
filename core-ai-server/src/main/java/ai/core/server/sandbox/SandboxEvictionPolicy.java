package ai.core.server.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Which sandbox of a user at its budget may be reclaimed to admit the next one. Conservative on
 * purpose: a persistent sandbox (OCG), a session with a running turn, a sandbox still materializing
 * and one used within the idle window all refuse, so eviction only ever costs a genuinely idle
 * session its cold workspace.
 *
 * @author stephen
 */
final class SandboxEvictionPolicy implements SandboxManager.EvictionPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxEvictionPolicy.class);

    private final SandboxService service;

    SandboxEvictionPolicy(SandboxService service) {
        this.service = service;
    }

    @Override
    public boolean isEvictable(SandboxManager.SandboxEntry entry, Instant now) {
        var sessionId = entry.sessionId;
        if (sessionId == null) return false;
        if (service.persistentSessionIds.contains(sessionId)) return false;
        var busy = service.sessionBusy;
        if (busy != null && busy.test(sessionId)) return false;
        var sandbox = service.sessionSandboxes.get(sessionId);
        if (!(sandbox instanceof LazySandbox lazy)) return false;
        if (!entry.sandbox.getId().equals(lazy.getId())) return false;
        if (lazy.isMaterializing()) return false;
        if (!lazy.isDelegateTracked()) return false;
        return Duration.between(entry.lastAccessAt, now).getSeconds() >= service.evictionMinIdleSeconds;
    }

    @Override
    public boolean evict(SandboxManager.SandboxEntry entry) {
        var sessionId = entry.sessionId;
        var sandbox = service.sessionSandboxes.get(sessionId);
        if (!(sandbox instanceof LazySandbox lazy)) return false;
        if (!SandboxReleaseSupport.capture(service, sessionId, entry.sandbox.getId())) {
            LOGGER.warn("skipping sandbox eviction, snapshot capture failed: sessionId={}, sandboxId={}",
                    sessionId, entry.sandbox.getId());
            return false;
        }
        service.sessionMcpProcesses.stopAll(sessionId, lazy);
        service.deleteSandboxBinding(sessionId);
        // Keep the LazySandbox registered: its next tool call re-acquires through the same quota path
        // instead of leaving the session with a dangling reference.
        lazy.close();
        LOGGER.info("sandbox evicted to free quota: sessionId={}, sandboxId={}", sessionId, entry.sandbox.getId());
        return true;
    }
}
