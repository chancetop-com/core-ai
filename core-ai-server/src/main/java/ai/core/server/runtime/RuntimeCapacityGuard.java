package ai.core.server.runtime;

import ai.core.server.messaging.TurnStateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides whether this pod can accept more work. Two signals are consulted, both cheap enough to read
 * on every admission check: heap pressure (sampled at most once per {@code SAMPLE_INTERVAL_MS}) and the
 * number of turns executing on this pod. Queue-depth and per-user signals are enforced closer to where
 * they live (session queue, run pool, sandbox manager) because only that component can act on them.
 *
 * @author stephen
 */
public class RuntimeCapacityGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeCapacityGuard.class);
    private static final long SAMPLE_INTERVAL_MS = 5000;
    private static final int RETRY_AFTER_SECONDS = 30;

    private final RuntimeLimitsProvider limitsProvider;
    private final TurnStateRegistry turnStateRegistry;

    private volatile RuntimeLimits cachedLimits = new RuntimeLimits();
    private volatile long limitsCachedAtMillis;
    private volatile double heapUsedPercent;
    private volatile long heapSampledAtMillis;
    private final AtomicLong rejectedTotal = new AtomicLong();
    private final AtomicLong saturatedTotal = new AtomicLong();

    public RuntimeCapacityGuard(RuntimeLimitsProvider limitsProvider, TurnStateRegistry turnStateRegistry) {
        this.limitsProvider = limitsProvider;
        this.turnStateRegistry = turnStateRegistry;
    }

    /**
     * @return null when this pod can take the request, otherwise the saturated scope
     *         (heap / pod_turns). Cross-pod limits are intentionally not consulted here: they need the
     *         shared counter that the session and sandbox layers own.
     */
    public String saturatedScope() {
        var limits = limits();
        if (heapUsedPercent() >= limits.heapWatermarkPercent) return "heap";
        if (inFlightTurns() >= limits.maxInFlightTurnsPerPod) return "pod_turns";
        return null;
    }

    public RuntimeLimits limits() {
        var now = System.currentTimeMillis();
        if (now - limitsCachedAtMillis >= SAMPLE_INTERVAL_MS) {
            try {
                cachedLimits = limitsProvider.get();
            } catch (Exception e) {
                // settings are read from Mongo: a transient failure must not take admissions down
                LOGGER.warn("failed to read runtime limits, keeping previous values", e);
            }
            limitsCachedAtMillis = now;
        }
        return cachedLimits;
    }

    public double heapUsedPercent() {
        var now = System.currentTimeMillis();
        if (now - heapSampledAtMillis >= SAMPLE_INTERVAL_MS) {
            heapUsedPercent = sampleHeapUsedPercent();
            heapSampledAtMillis = now;
        }
        return heapUsedPercent;
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
        LOGGER.warn("runtime capacity saturated (warn-only, request allowed), scope={}, heapUsedPercent={}, inFlightTurns={}",
                scope, heapUsedPercent(), inFlightTurns());
    }

    public void recordRejected(String scope) {
        rejectedTotal.incrementAndGet();
        LOGGER.warn("runtime capacity exceeded, request rejected, scope={}, heapUsedPercent={}, inFlightTurns={}",
                scope, heapUsedPercent(), inFlightTurns());
    }

    public long rejectedTotal() {
        return rejectedTotal.get();
    }

    public long saturatedTotal() {
        return saturatedTotal.get();
    }

    public int retryAfterSeconds() {
        return RETRY_AFTER_SECONDS;
    }

    private double sampleHeapUsedPercent() {
        var usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = usage.getMax() > 0 ? usage.getMax() : Runtime.getRuntime().maxMemory();
        if (max <= 0) return 0;
        return Math.round(usage.getUsed() * 10000.0 / max) / 100.0;
    }
}
