package ai.core.server.runtime;

import ai.core.server.messaging.TurnStateRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * @author core-ai
 */
class RuntimeCapacityGuardTest {
    private static JvmMemoryMetrics.Snapshot snapshot(double heapPercent, double oldGenPercent,
                                                      double afterGcPercent, double containerPercent) {
        return new JvmMemoryMetrics.Snapshot(
                Math.round(heapPercent * 10), 1_000L, heapPercent,
                null, null,
                Math.round(afterGcPercent * 10), afterGcPercent,
                1L, 100L,
                Math.round(containerPercent * 10), 100L, containerPercent,
                null,
                Math.round(oldGenPercent * 10), oldGenPercent,
                "young", 0L);
    }

    private final RuntimeCapacityGuard guard = new RuntimeCapacityGuard(mock(RuntimeLimitsProvider.class),
            mock(TurnStateRegistry.class));
    private final RuntimeLimits limits = new RuntimeLimits();

    @Test
    void youngGcPressureDoesNotSaturateWhileOldGenIsLow() {
        // 92% instantaneous heap, 40% Old Gen: a Young GC, not retained pressure
        guard.updateState(snapshot(92, 40, 40, 50), 1_000, limits);
        guard.updateState(snapshot(92, 40, 40, 50), 6_000, limits);

        assertFalse(guard.isSaturated());
    }

    @Test
    void twoOldGenSamplesAboveWatermarkEnterRetainedSaturation() {
        guard.updateState(snapshot(70, 86, 60, 60), 1_000, limits);
        assertFalse(guard.isSaturated());

        guard.updateState(snapshot(72, 86, 62, 62), 6_000, limits);
        assertTrue(guard.isSaturated());
        assertEquals("heap_retained", guard.currentSaturatedScope());
    }

    @Test
    void exitNeedsThreeBelowHysteresisSamplesAndTheCooldown() {
        guard.updateState(snapshot(70, 86, 60, 60), 1_000, limits);
        guard.updateState(snapshot(70, 86, 60, 60), 6_000, limits);
        assertTrue(guard.isSaturated());

        guard.updateState(snapshot(60, 70, 50, 50), 11_000, limits);
        guard.updateState(snapshot(60, 70, 50, 50), 16_000, limits);
        guard.updateState(snapshot(60, 70, 50, 50), 21_000, limits);
        // cooldown has not elapsed yet
        assertTrue(guard.isSaturated());

        guard.updateState(snapshot(60, 70, 50, 50), 67_000, limits);
        assertFalse(guard.isSaturated());
        assertNull(guard.currentSaturatedScope());
    }

    @Test
    void containerPressureHasItsOwnEntryAndScope() {
        guard.updateState(snapshot(50, 30, 40, 91), 1_000, limits);
        guard.updateState(snapshot(50, 30, 40, 92), 6_000, limits);

        assertEquals("container", guard.currentSaturatedScope());
    }

    @Test
    void aCatastrophicHeapTripsEvenWhenOldGenLooksHealthy() {
        guard.updateState(snapshot(96, 40, 40, 50), 1_000, limits);
        guard.updateState(snapshot(97, 40, 40, 50), 6_000, limits);

        assertEquals("heap_catastrophic", guard.currentSaturatedScope());
    }
}
