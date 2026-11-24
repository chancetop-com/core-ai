package ai.core.server.runtime;

import ai.core.server.messaging.TurnStateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides whether this pod can accept more work. Admission reads <em>retained</em> pressure: the G1 Old
 * Gen after the last collection (with the after-GC value as an auxiliary signal on collectors that do
 * not expose it) and the container working set as a hard fallback. The instantaneous heap only trips a
 * catastrophic guard, so a Young GC that happens to run at 90% no longer refuses traffic.
 *
 * <p>The state machine is hysteretic: entering needs {@code ENTER_SAMPLES} consecutive 5s samples,
 * leaving needs {@code EXIT_SAMPLES} below watermark minus the hysteresis, plus a cooldown. A stale
 * signal fails open (a broken sampler must not reject every request); a container at or above its hard
 * limit is still refused because that is an OOM risk independent of the heap sampler.
 *
 * @author stephen
 */
public class RuntimeCapacityGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeCapacityGuard.class);
    private static final long SAMPLE_INTERVAL_MS = 5000;
    private static final int RETRY_AFTER_SECONDS = 30;
    private static final int ENTER_SAMPLES = 2;
    private static final int EXIT_SAMPLES = 3;
    private static final int HYSTERESIS_PERCENT = 10;
    private static final long COOLDOWN_MS = 60_000;
    private static final long SIGNAL_MAX_AGE_MS = 15_000;
    private static final int CATASTROPHIC_HEAP_PERCENT = 95;
    private static final int CONTAINER_ENTER_PERCENT = 90;
    private static final int CONTAINER_HARD_PERCENT = 95;
    private static final int CONTAINER_EXIT_PERCENT = 85;
    private static final int EXIT_HEAP_PERCENT = 90;

    private static Double retainedPercent(JvmMemoryMetrics.Snapshot snapshot) {
        if (snapshot.oldGenPercent() != null) return snapshot.oldGenPercent();
        // a concurrent cycle reports before == after; only a collecting event is a usable after-GC value
        if (snapshot.heapAfterGcPercent() != null && snapshot.gcReclaimedBytes() != null
                && snapshot.gcReclaimedBytes() > 0 && !"concurrent".equals(snapshot.gcKind())) {
            return snapshot.heapAfterGcPercent();
        }
        return null;
    }

    private static boolean atLeast(Double value, double threshold) {
        return value != null && value >= threshold;
    }

    private final RuntimeLimitsProvider limitsProvider;
    private final TurnStateRegistry turnStateRegistry;

    private volatile RuntimeLimits cachedLimits = new RuntimeLimits();
    private volatile long limitsCachedAtMillis;
    private volatile Signal signal;
    private volatile long signalSampledAtMillis;
    private volatile boolean signalStale;
    private volatile boolean saturated;
    private volatile String saturatedScope;
    private volatile long lastTransitionMillis;
    private int enterCount;
    private int containerEnterCount;
    private int catastrophicCount;
    private int exitCount;
    private final AtomicLong rejectedTotal = new AtomicLong();
    private final AtomicLong saturatedTotal = new AtomicLong();
    private final AtomicLong transitionsTotal = new AtomicLong();

    public RuntimeCapacityGuard(RuntimeLimitsProvider limitsProvider, TurnStateRegistry turnStateRegistry) {
        this.limitsProvider = limitsProvider;
        this.turnStateRegistry = turnStateRegistry;
    }

    /**
     * @return null when this pod can take the request, otherwise the saturated scope
     *         (heap_retained / heap_catastrophic / container / pod_turns). Cross-pod limits are
     *         intentionally not consulted here: they need the shared counter that the session and
     *         sandbox layers own.
     */
    public String saturatedScope() {
        var limits = limits();
        if (inFlightTurns() >= limits.maxInFlightTurnsPerPod) return "pod_turns";
        maybeSample(limits);
        var current = signal;
        if (current == null) return null;
        var age = System.currentTimeMillis() - current.sampledAtMillis();
        if (age > SIGNAL_MAX_AGE_MS) {
            signalStale = true;
            if (atLeast(current.snapshot().containerMemoryPercent(), CONTAINER_HARD_PERCENT)) return "container";
            return saturated ? saturatedScope : null;
        }
        if (signalStale) {
            LOGGER.warn("runtime pressure signal is fresh again, admitting normally");
            signalStale = false;
        }
        return saturated ? saturatedScope : null;
    }

    public RuntimeLimits limits() {
        var now = System.currentTimeMillis();
        if (now - limitsCachedAtMillis >= SAMPLE_INTERVAL_MS) {
            try {
                var resolved = limitsProvider.get();
                if (resolved != null) cachedLimits = resolved;
            } catch (Exception e) {
                // settings are read from Mongo: a transient failure must not take admissions down
                LOGGER.warn("failed to read runtime limits, keeping previous values", e);
            }
            limitsCachedAtMillis = now;
        }
        return cachedLimits;
    }

    public double heapUsedPercent() {
        maybeSample(limits());
        var current = signal;
        return current == null || current.snapshot().heapUsedPercent() == null ? 0 : current.snapshot().heapUsedPercent();
    }

    public Double oldGenPercent() {
        var current = signal;
        return current == null ? null : current.snapshot().oldGenPercent();
    }

    public Double heapAfterGcPercent() {
        var current = signal;
        return current == null ? null : current.snapshot().heapAfterGcPercent();
    }

    public Double containerMemoryPercent() {
        var current = signal;
        return current == null ? null : current.snapshot().containerMemoryPercent();
    }

    public String gcKind() {
        var current = signal;
        return current == null ? null : current.snapshot().gcKind();
    }

    public Long signalAgeMillis() {
        var current = signal;
        return current == null ? null : Math.max(0, System.currentTimeMillis() - current.sampledAtMillis());
    }

    public boolean isSaturated() {
        return saturated;
    }

    String currentSaturatedScope() {
        return saturated ? saturatedScope : null;
    }

    public long heapMaxBytes() {
        var max = Runtime.getRuntime().maxMemory();
        return max <= 0 ? -1 : max;
    }

    public long heapUsedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    public int inFlightTurns() {
        return turnStateRegistry.runningCount();
    }

    /**
     * Per-user turn concurrency. Only consulted where the caller is the user (chat messages), and it
     * refuses the *new* message instead of interrupting turns that are already running.
     */
    public String saturatedUserScope(String userId) {
        var limits = limits();
        if (userId == null || limits.maxConcurrentTurnsPerUser <= 0) return null;
        return turnStateRegistry.inFlightTurns(userId) >= limits.maxConcurrentTurnsPerUser ? "user_turns" : null;
    }

    /** Saturation that was allowed through because enforcement is off — the calibration signal. */
    public void recordSaturated(String scope) {
        saturatedTotal.incrementAndGet();
        LOGGER.warn("runtime capacity saturated (warn-only, request allowed), scope={}, retainedPercent={}, heapUsedPercent={}, containerPercent={}, saturated={}",
                scope, oldGenPercent(), heapUsedPercent(), containerMemoryPercent(), saturated);
    }

    public void recordRejected(String scope) {
        rejectedTotal.incrementAndGet();
        LOGGER.warn("runtime capacity exceeded, request rejected, scope={}, retainedPercent={}, heapUsedPercent={}, containerPercent={}",
                scope, oldGenPercent(), heapUsedPercent(), containerMemoryPercent());
    }

    public long rejectedTotal() {
        return rejectedTotal.get();
    }

    public long saturatedTotal() {
        return saturatedTotal.get();
    }

    public long transitionsTotal() {
        return transitionsTotal.get();
    }

    public int retryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }

    private void maybeSample(RuntimeLimits limits) {
        synchronized (this) {
            var now = System.currentTimeMillis();
            if (now - signalSampledAtMillis < SAMPLE_INTERVAL_MS) return;
            try {
                var snapshot = JvmMemoryMetrics.snapshot();
                signal = new Signal(snapshot, now);
                updateState(snapshot, now, limits);
            } catch (RuntimeException e) {
                // keep the last good signal; its age makes the next admission fail open
                LOGGER.warn("failed to sample runtime pressure, keeping previous signal", e);
            } finally {
                signalSampledAtMillis = now;
            }
        }
    }

    void updateState(JvmMemoryMetrics.Snapshot snapshot, long now, RuntimeLimits limits) {
        var retained = retainedPercent(snapshot);
        if (!saturated) {
            enterCount = atLeast(retained, limits.heapWatermarkPercent) ? enterCount + 1 : 0;
            containerEnterCount = atLeast(snapshot.containerMemoryPercent(), CONTAINER_ENTER_PERCENT)
                    ? containerEnterCount + 1 : 0;
            catastrophicCount = atLeast(snapshot.heapUsedPercent(), CATASTROPHIC_HEAP_PERCENT)
                    ? catastrophicCount + 1 : 0;
            if (enterCount >= ENTER_SAMPLES) {
                transition("heap_retained", now);
            } else if (containerEnterCount >= ENTER_SAMPLES) {
                transition("container", now);
            } else if (catastrophicCount >= ENTER_SAMPLES) {
                transition("heap_catastrophic", now);
            }
            return;
        }

        var retainedBelowExit = retained != null
                ? retained <= limits.heapWatermarkPercent - HYSTERESIS_PERCENT
                : snapshot.containerMemoryPercent() != null && snapshot.containerMemoryPercent() < CONTAINER_EXIT_PERCENT;
        var heapBelowExit = snapshot.heapUsedPercent() == null || snapshot.heapUsedPercent() < EXIT_HEAP_PERCENT;
        var containerBelowExit = snapshot.containerMemoryPercent() == null
                || snapshot.containerMemoryPercent() < CONTAINER_ENTER_PERCENT;
        exitCount = retainedBelowExit && heapBelowExit && containerBelowExit ? exitCount + 1 : 0;
        if (exitCount >= EXIT_SAMPLES && now - lastTransitionMillis >= COOLDOWN_MS) {
            transition(null, now);
        }
    }

    private void transition(String scope, long now) {
        saturated = scope != null;
        saturatedScope = scope;
        lastTransitionMillis = now;
        enterCount = 0;
        containerEnterCount = 0;
        catastrophicCount = 0;
        exitCount = 0;
        transitionsTotal.incrementAndGet();
        if (scope == null) {
            LOGGER.info("runtime pressure recovered, admitting new work");
        } else {
            LOGGER.warn("runtime pressure saturated, scope={}, retainedPercent={}, heapUsedPercent={}, containerPercent={}",
                    scope, oldGenPercent(), heapUsedPercent(), containerMemoryPercent());
        }
    }

    private record Signal(JvmMemoryMetrics.Snapshot snapshot, long sampledAtMillis) {
    }
}
