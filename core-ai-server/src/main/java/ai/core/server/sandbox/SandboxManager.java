package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.runtime.RuntimeLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class SandboxManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxProvider provider;
    private final Map<String, SandboxEntry> activeSandboxes = new ConcurrentHashMap<>();
    private final AtomicInteger acquireCount = new AtomicInteger(0);
    private final AtomicInteger releaseCount = new AtomicInteger(0);
    private final AtomicInteger untrackedReleaseCount = new AtomicInteger(0);
    private final AtomicInteger rejectedAcquireCount = new AtomicInteger(0);
    private final AtomicLong createsTotal = new AtomicLong();
    private final AtomicLong createsFailedTotal = new AtomicLong();
    private volatile long lastCreateMs;
    // set by RuntimeGovernanceModule: quotas are read live from system settings
    public Supplier<RuntimeLimits> limitsSupplier;

    public SandboxManager(SandboxProvider provider) {
        this.provider = provider;
    }

    /** Sandboxes this pod is holding plus the cluster-wide counts the provider can report. */
    public SandboxFleet fleet() {
        var fleet = provider instanceof SandboxFleetAware aware ? aware.providerFleet() : new SandboxFleet();
        fleet.createsTotal = createsTotal.get();
        fleet.createsFailedTotal = createsFailedTotal.get();
        fleet.lastCreateMs = lastCreateMs == 0 ? null : lastCreateMs;
        return fleet;
    }

    /** Sandbox creations refused by the pod-level sandbox quota. */
    public long rejectedTotal() {
        return rejectedAcquireCount.get();
    }

    public void recordRejection() {
        rejectedAcquireCount.incrementAndGet();
    }

    /** See {@link SandboxProvider#workspaceWritable()}. */
    public boolean workspaceWritable() {
        return provider == null || provider.workspaceWritable();
    }

    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        enforceQuota(userId, config);
        var startedAt = System.currentTimeMillis();
        Sandbox sandbox;
        try {
            sandbox = provider.acquire(config, sessionId, userId);
        } catch (RuntimeException e) {
            createsFailedTotal.incrementAndGet();
            throw e;
        }
        createsTotal.incrementAndGet();
        lastCreateMs = System.currentTimeMillis() - startedAt;
        var entry = new SandboxEntry(sandbox, sessionId, userId, config, Instant.now());

        activeSandboxes.put(sandbox.getId(), entry);
        acquireCount.incrementAndGet();

        LOGGER.debug("sandbox acquired: id={}, sessionId={}, activeCount={}", sandbox.getId(), sessionId, activeSandboxes.size());

        return sandbox;
    }

    /**
     * Pod and per-user budgets, checked before anything is provisioned: a sandbox that cannot be
     * counted must not be created, otherwise the failure shows up as a node outage instead of a
     * clear tool error. Attach paths are deliberately exempt — they adopt a sandbox that already exists.
     */
    private void enforceQuota(String userId, SandboxConfig config) {
        var limits = currentLimits();
        if (limits.maxSandboxesPerPod > 0 && activeSandboxes.size() >= limits.maxSandboxesPerPod) {
            recordRejection();
            throw new SandboxQuotaExceededException("sandbox capacity reached on this pod ("
                    + activeSandboxes.size() + "/" + limits.maxSandboxesPerPod + "), retry later");
        }
        if (userId == null) return;

        int count = 0;
        int memoryMb = 0;
        for (var entry : activeSandboxes.values()) {
            if (!userId.equals(entry.userId)) continue;
            count++;
            memoryMb += memoryOf(entry.config);
        }
        if (limits.maxSandboxesPerUser > 0 && count >= limits.maxSandboxesPerUser) {
            recordRejection();
            throw new SandboxQuotaExceededException("sandbox limit reached for this user ("
                    + count + "/" + limits.maxSandboxesPerUser + "), release a session first");
        }
        int projected = memoryMb + memoryOf(config);
        if (limits.maxSandboxMemoryMbPerUser > 0 && projected > limits.maxSandboxMemoryMbPerUser) {
            recordRejection();
            throw new SandboxQuotaExceededException("sandbox memory budget reached for this user ("
                    + projected + "Mi > " + limits.maxSandboxMemoryMbPerUser + "Mi)");
        }
    }

    private int memoryOf(SandboxConfig config) {
        if (config == null || config.memoryLimitMb == null) return SandboxConstants.DEFAULT_MEMORY_LIMIT_MB;
        return config.memoryLimitMb;
    }

    private RuntimeLimits currentLimits() {
        if (limitsSupplier == null) return new RuntimeLimits();
        try {
            return limitsSupplier.get();
        } catch (Exception e) {
            LOGGER.warn("failed to read runtime limits for sandbox quota, allowing the sandbox", e);
            return new RuntimeLimits();
        }
    }

    public Optional<Sandbox> attach(String sandboxId, SandboxConfig config, String sessionId, String userId) {
        var sandbox = provider.attach(sandboxId, config, sessionId, userId);
        sandbox.ifPresent(attached -> {
            var entry = new SandboxEntry(attached, sessionId, userId, config, Instant.now());
            activeSandboxes.put(attached.getId(), entry);
            acquireCount.incrementAndGet();
            LOGGER.info("sandbox attached: id={}, sessionId={}, activeCount={}", attached.getId(), sessionId, activeSandboxes.size());
        });
        return sandbox;
    }

    /**
     * Straight pass-through to the provider's attach, with NO map registration and NO
     * acquire-count bump. Used by transient runtime resolution (e.g. terminal address
     * lookup), which must resolve a sandbox address without joining its lifecycle:
     * no entry in {@code activeSandboxes}, no release ever owed for this call.
     */
    public Optional<Sandbox> attachTransient(String sandboxId, SandboxConfig config, String sessionId, String userId) {
        return provider.attach(sandboxId, config, sessionId, userId);
    }

    public Sandbox get(String sandboxId) {
        var entry = activeSandboxes.get(sandboxId);
        return entry == null ? null : entry.sandbox;
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
            // Sandbox not tracked — likely already cleaned up by cleanupExpired() or the claim was
            // created outside the tracking path. Still release the provider resource as a safety net.
            provider.release(sandbox);
            untrackedReleaseCount.incrementAndGet();
            LOGGER.debug("released sandbox not tracked: id={}, hostname={}, image={}, status={}, untrackedTotal={}",
                    sandbox.getId(), sandbox.hostname(), sandbox.image(), sandbox.getStatus(), untrackedReleaseCount.get());
        }
    }

    public SandboxStatus getStatus(Sandbox sandbox) {
        return provider.getStatus(sandbox);
    }

    public void cleanupExpired() {
        cleanupExpired(null);
    }

    public void cleanupExpired(Consumer<SandboxEntry> beforeRelease) {
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
            if (beforeRelease != null) {
                try {
                    beforeRelease.accept(entry);
                } catch (Exception e) {
                    LOGGER.warn("sandbox pre-release hook failed, releasing anyway: id={}, sessionId={}",
                            entry.sandbox.getId(), entry.sessionId, e);
                }
            }
            LOGGER.info("cleaning up expired sandbox: id={}, sessionId={}, age={}s",
                    entry.sandbox.getId(), entry.sessionId,
                    Duration.between(entry.createdAt, now).getSeconds());
            release(entry.sandbox);
        }

        if (!expired.isEmpty()) {
            LOGGER.info("cleaned up {} expired sandboxes, {} remaining", expired.size(), activeSandboxes.size());
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
                "totalReleased", releaseCount.get(),
                "totalUntrackedReleased", untrackedReleaseCount.get()
        );
    }

    public void renew(String sandboxId) {
        var entry = activeSandboxes.get(sandboxId);
        if (entry == null) return;

        entry.createdAt = Instant.now();
        var timeout = entry.config.timeoutSeconds != null
                ? entry.config.timeoutSeconds
                : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;
        // Also extend the externally-tracked deadline (e.g. K8s shutdownTime), otherwise the in-memory
        // renewal is defeated by the provider/K8s deleting the sandbox at its original deadline.
        provider.renew(entry.sandbox, entry.config);
        LOGGER.debug("sandbox renewed: id={}, sessionId={}, timeout={}s", sandboxId, entry.sessionId, timeout);
    }

    /**
     * Lightweight in-memory TTL bump, called on every tool execution in a long agent loop.
     * Only propagates to provider (K8s patch) when past half the TTL to avoid excessive API calls.
     */
    public void touch(String sandboxId) {
        var entry = activeSandboxes.get(sandboxId);
        if (entry == null) return;

        var now = Instant.now();
        var timeout = entry.config.timeoutSeconds != null
                ? entry.config.timeoutSeconds
                : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;

        // Always bump in-memory TTL so cleanupExpired() won't release this sandbox mid-loop
        entry.createdAt = now;

        // Only call provider.renew() when past half the TTL since the last provider call
        var elapsed = Duration.between(entry.lastProviderRenewAt, now);
        if (elapsed.compareTo(Duration.ofSeconds(timeout / 2)) < 0) {
            return;
        }

        entry.lastProviderRenewAt = now;
        provider.renew(entry.sandbox, entry.config);
        LOGGER.debug("sandbox touched (provider): id={}, sessionId={}, timeout={}s", sandboxId, entry.sessionId, timeout);
    }

    public static final class SandboxEntry {
        public final Sandbox sandbox;
        public final String sessionId;
        public final String userId;
        public final SandboxConfig config;
        volatile Instant createdAt;
        volatile Instant lastProviderRenewAt;

        SandboxEntry(Sandbox sandbox, String sessionId, String userId, SandboxConfig config, Instant createdAt) {
            this.sandbox = sandbox;
            this.sessionId = sessionId;
            this.userId = userId;
            this.config = config;
            this.createdAt = createdAt;
            this.lastProviderRenewAt = createdAt;
        }
    }
}
