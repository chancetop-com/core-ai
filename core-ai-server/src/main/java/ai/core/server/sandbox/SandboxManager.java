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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class SandboxManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxProvider provider;
    private final Map<String, SandboxEntry> activeSandboxes = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();
    private final AtomicInteger acquireCount = new AtomicInteger(0);
    private final AtomicInteger releaseCount = new AtomicInteger(0);
    private final AtomicInteger untrackedReleaseCount = new AtomicInteger(0);
    private final AtomicInteger rejectedAcquireCount = new AtomicInteger(0);
    private final AtomicLong createsTotal = new AtomicLong();
    private final AtomicLong createsFailedTotal = new AtomicLong();
    private final AtomicLong evictedTotal = new AtomicLong();
    private volatile long lastCreateMs;
    // set by RuntimeGovernanceModule: quotas are read live from system settings
    public Supplier<RuntimeLimits> limitsSupplier;
    // set by SandboxService: decides which sandbox of the requesting user may be reclaimed and reclaims it
    public volatile EvictionPolicy evictionPolicy;

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

    /** Sandboxes reclaimed from an idle session to make room for the requesting user's next sandbox. */
    public long evictedTotal() {
        return evictedTotal.get();
    }

    /** See {@link SandboxProvider#workspaceWritable()}. */
    public boolean workspaceWritable() {
        return provider == null || provider.workspaceWritable();
    }

    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        // Quota check, eviction and registration must be one critical section per user: two concurrent
        // acquires would otherwise both observe free budget and overshoot it.
        var lock = userLock(userId);
        lock.lock();
        try {
            enforceQuota(userId, config, sessionId);
            var startedAt = System.currentTimeMillis();
            var sandbox = createSandbox(config, sessionId, userId);
            createsTotal.incrementAndGet();
            lastCreateMs = System.currentTimeMillis() - startedAt;
            var entry = new SandboxEntry(sandbox, sessionId, userId, config, Instant.now());

            activeSandboxes.put(sandbox.getId(), entry);
            acquireCount.incrementAndGet();

            LOGGER.debug("sandbox acquired: id={}, sessionId={}, activeCount={}", sandbox.getId(), sessionId, activeSandboxes.size());

            return sandbox;
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock userLock(String userId) {
        return userLocks.computeIfAbsent(userId != null ? userId : "", ignored -> new ReentrantLock());
    }

    private Sandbox createSandbox(SandboxConfig config, String sessionId, String userId) {
        try {
            return provider.acquire(config, sessionId, userId);
        } catch (RuntimeException e) {
            createsFailedTotal.incrementAndGet();
            throw e;
        }
    }

    /**
     * Pod and per-user budgets, checked before anything is provisioned: a sandbox that cannot be
     * counted must not be created, otherwise the failure shows up as a node outage instead of a
     * clear tool error. Attach paths are deliberately exempt — they adopt a sandbox that already exists.
     */
    private void enforceQuota(String userId, SandboxConfig config, String requestingSessionId) {
        var limits = currentLimits();
        if (limits.maxSandboxesPerPod > 0 && activeSandboxes.size() >= limits.maxSandboxesPerPod) {
            recordRejection();
            throw new SandboxQuotaExceededException("sandbox capacity reached on this pod ("
                    + activeSandboxes.size() + "/" + limits.maxSandboxesPerPod + "), retry later");
        }
        if (userId == null) return;
        if (withinUserLimits(userId, config, limits)) return;

        var requestedMb = memoryOf(config);
        if (limits.maxSandboxMemoryMbPerUser > 0 && requestedMb > limits.maxSandboxMemoryMbPerUser) {
            recordRejection();
            throw new SandboxQuotaExceededException("sandbox memory (" + requestedMb
                    + "Mi) exceeds the per-user budget (" + limits.maxSandboxMemoryMbPerUser + "Mi)");
        }
        evictForUser(userId, config, limits, requestingSessionId);
        if (withinUserLimits(userId, config, limits)) return;

        var usage = userUsage(userId);
        if (limits.maxSandboxesPerUser > 0 && usage.count >= limits.maxSandboxesPerUser) {
            recordRejection();
            throw new SandboxQuotaExceededException("sandbox limit reached for this user ("
                    + usage.count + "/" + limits.maxSandboxesPerUser + "), release a session first");
        }
        recordRejection();
        throw new SandboxQuotaExceededException("sandbox memory budget reached for this user ("
                + (usage.memoryMb + memoryOf(config)) + "Mi > " + limits.maxSandboxMemoryMbPerUser + "Mi)");
    }

    private boolean withinUserLimits(String userId, SandboxConfig config, RuntimeLimits limits) {
        var usage = userUsage(userId);
        if (limits.maxSandboxesPerUser > 0 && usage.count >= limits.maxSandboxesPerUser) return false;
        return limits.maxSandboxMemoryMbPerUser <= 0
                || usage.memoryMb + memoryOf(config) <= limits.maxSandboxMemoryMbPerUser;
    }

    private UserUsage userUsage(String userId) {
        int count = 0;
        int memoryMb = 0;
        for (var entry : activeSandboxes.values()) {
            if (!userId.equals(entry.userId)) continue;
            count++;
            memoryMb += memoryOf(entry.config);
        }
        return new UserUsage(count, memoryMb);
    }

    /**
     * Reclaims the requesting user's least recently used sandboxes until the new one fits. Only the
     * user's own sandboxes are candidates: freeing another user's budget to admit this one would turn
     * a per-user quota into a global free-for-all.
     */
    private void evictForUser(String userId, SandboxConfig config, RuntimeLimits limits, String requestingSessionId) {
        var policy = evictionPolicy;
        if (policy == null) return;

        var now = Instant.now();
        var candidates = new ArrayList<SandboxEntry>();
        for (var entry : activeSandboxes.values()) {
            if (!userId.equals(entry.userId)) continue;
            if (Objects.equals(requestingSessionId, entry.sessionId)) continue;
            candidates.add(entry);
        }
        candidates.sort(Comparator.comparing(entry -> entry.lastAccessAt));

        for (var candidate : candidates) {
            if (withinUserLimits(userId, config, limits)) return;
            try {
                if (!policy.isEvictable(candidate, now)) continue;
                if (!policy.evict(candidate)) continue;
            } catch (RuntimeException e) {
                LOGGER.warn("sandbox eviction failed, trying the next candidate: id={}, sessionId={}",
                        candidate.sandbox.getId(), candidate.sessionId, e);
                continue;
            }
            if (activeSandboxes.containsKey(candidate.sandbox.getId())) {
                LOGGER.warn("eviction reported success but the sandbox is still tracked: id={}, sessionId={}",
                        candidate.sandbox.getId(), candidate.sessionId);
                continue;
            }
            evictedTotal.incrementAndGet();
            LOGGER.info("evicted sandbox to free quota: id={}, sessionId={}, userId={}, activeCount={}",
                    candidate.sandbox.getId(), candidate.sessionId, userId, activeSandboxes.size());
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
        // Adoption is not refused by the budget, but it must not race the acquire path's accounting:
        // an attach that lands between a quota check and its registration would be invisible to it.
        var lock = userLock(userId);
        lock.lock();
        try {
            var sandbox = provider.attach(sandboxId, config, sessionId, userId);
            sandbox.ifPresent(attached -> {
                var entry = new SandboxEntry(attached, sessionId, userId, config, Instant.now());
                activeSandboxes.put(attached.getId(), entry);
                acquireCount.incrementAndGet();
                LOGGER.info("sandbox attached: id={}, sessionId={}, activeCount={}", attached.getId(), sessionId, activeSandboxes.size());
            });
            return sandbox;
        } finally {
            lock.unlock();
        }
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

            if (Duration.between(entry.lastAccessAt, now).compareTo(maxAge) > 0) {
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
                    Duration.between(entry.lastAccessAt, now).getSeconds());
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
                "totalUntrackedReleased", untrackedReleaseCount.get(),
                "totalEvicted", evictedTotal.get()
        );
    }

    public void renew(String sandboxId) {
        var entry = activeSandboxes.get(sandboxId);
        if (entry == null) return;

        entry.lastAccessAt = Instant.now();
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

        // Always bump the TTL/LRU anchor so cleanupExpired() won't release this sandbox mid-loop
        entry.lastAccessAt = now;

        // Only call provider.renew() when past half the TTL since the last provider call
        var elapsed = Duration.between(entry.lastProviderRenewAt, now);
        if (elapsed.compareTo(Duration.ofSeconds(timeout / 2)) < 0) {
            return;
        }

        entry.lastProviderRenewAt = now;
        provider.renew(entry.sandbox, entry.config);
        LOGGER.debug("sandbox touched (provider): id={}, sessionId={}, timeout={}s", sandboxId, entry.sessionId, timeout);
    }

    /**
     * Decides which sandbox of a saturated user may be reclaimed, and reclaims it. Implemented by the
     * sandbox service, because only it knows which sessions are persistent, busy or worth snapshotting;
     * the manager owns the candidate order (least recently used first) and the accounting.
     */
    public interface EvictionPolicy {
        boolean isEvictable(SandboxEntry entry, Instant now);

        /** @return true only when the provider resource is gone and {@link #activeSandboxes} lost the entry */
        boolean evict(SandboxEntry entry);
    }

    private record UserUsage(int count, int memoryMb) {
    }

    public static final class SandboxEntry {
        public final Sandbox sandbox;
        public final String sessionId;
        public final String userId;
        public final SandboxConfig config;
        public final Instant createdAt;
        /** Renewal anchor for the TTL and the LRU order: bumped by touch()/renew(), unlike createdAt. */
        volatile Instant lastAccessAt;
        volatile Instant lastProviderRenewAt;

        SandboxEntry(Sandbox sandbox, String sessionId, String userId, SandboxConfig config, Instant createdAt) {
            this.sandbox = sandbox;
            this.sessionId = sessionId;
            this.userId = userId;
            this.config = config;
            this.createdAt = createdAt;
            this.lastAccessAt = createdAt;
            this.lastProviderRenewAt = createdAt;
        }
    }
}
