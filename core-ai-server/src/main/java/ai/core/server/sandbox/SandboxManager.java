package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author stephen
 */
public class SandboxManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxProvider provider;
    private final Map<String, SandboxEntry> activeSandboxes = new ConcurrentHashMap<>();
    private final AtomicInteger acquireCount = new AtomicInteger(0);
    private final AtomicInteger releaseCount = new AtomicInteger(0);

    public SandboxManager(SandboxProvider provider) {
        this.provider = provider;
    }

    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        var sandbox = provider.acquire(config, sessionId, userId);
        var entry = new SandboxEntry(sandbox, sessionId, userId, config, Instant.now());

        // Use sandbox ID as key
        activeSandboxes.put(sandbox.getId(), entry);
        acquireCount.incrementAndGet();

        LOGGER.debug("sandbox acquired: id={}, sessionId={}, activeCount={}",
                sandbox.getId(), sessionId, activeSandboxes.size());

        return sandbox;
    }

    public void release(Sandbox sandbox) {
        if (sandbox == null) return;

        var entry = activeSandboxes.remove(sandbox.getId());
        if (entry != null) {
            provider.release(sandbox);
            releaseCount.incrementAndGet();

            LOGGER.debug("sandbox released: id={}, sessionId={}, activeCount={}",
                    sandbox.getId(), entry.sessionId, activeSandboxes.size());
        } else {
            // Sandbox not tracked, still release it
            provider.release(sandbox);
            LOGGER.warn("released sandbox not tracked: id={}", sandbox.getId());
        }
    }

    public SandboxStatus getStatus(Sandbox sandbox) {
        return provider.getStatus(sandbox);
    }

    public void cleanupExpired() {
        var now = Instant.now();
        var expired = new ArrayList<SandboxEntry>();

        for (var entry : activeSandboxes.values()) {
            var timeout = entry.config.timeoutSeconds != null
                    ? entry.config.timeoutSeconds
                    : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;
            var maxAge = Duration.ofSeconds(timeout);

            if (Duration.between(entry.createdAt, now).compareTo(maxAge) > 0) {
                expired.add(entry);
            }
        }

        for (var entry : expired) {
            LOGGER.info("cleaning up expired sandbox: id={}, sessionId={}, age={}s",
                    entry.sandbox.getId(), entry.sessionId,
                    Duration.between(entry.createdAt, now).getSeconds());
            release(entry.sandbox);
        }

        if (!expired.isEmpty()) {
            LOGGER.info("cleaned up {} expired sandboxes, {} remaining",
                    expired.size(), activeSandboxes.size());
        }
    }

    public int activeCount() {
        return activeSandboxes.size();
    }

    public List<SandboxEntry> getActiveSandboxes() {
        return new ArrayList<>(activeSandboxes.values());
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "activeCount", activeSandboxes.size(),
                "totalAcquired", acquireCount.get(),
                "totalReleased", releaseCount.get()
        );
    }

    public record SandboxEntry(Sandbox sandbox,
                               String sessionId,
                               String userId,
                               SandboxConfig config,
                               Instant createdAt) {
    }
}
