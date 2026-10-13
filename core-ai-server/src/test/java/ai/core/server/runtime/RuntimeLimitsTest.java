package ai.core.server.runtime;

import ai.core.server.domain.SystemSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class RuntimeLimitsTest {
    @Test
    void fallsBackToShippedDefaultsWhenNothingIsConfigured() {
        var limits = RuntimeLimits.resolve(new SystemSettings());
        assertFalse(limits.admissionEnabled);
        assertEquals(RuntimeLimits.DEFAULT_HEAP_WATERMARK_PERCENT, limits.heapWatermarkPercent);
        assertEquals(RuntimeLimits.DEFAULT_MAX_IN_FLIGHT_TURNS_PER_POD, limits.maxInFlightTurnsPerPod);
        assertEquals(RuntimeLimits.DEFAULT_MAX_SESSIONS_PER_USER, limits.maxSessionsPerUser);
        assertEquals(RuntimeLimits.DEFAULT_TURN_TIMEOUT_SECONDS, limits.turnTimeoutSeconds);
        assertEquals(0, limits.maxInFlightTurnsGlobal);
        assertEquals(0, limits.maxSandboxesPerAgent);
    }

    @Test
    void resolvesNullDocumentToOneUsableSetOfDefaults() {
        var limits = RuntimeLimits.resolve(null);
        assertEquals(RuntimeLimits.DEFAULT_HEAP_WATERMARK_PERCENT, limits.heapWatermarkPercent);
        assertEquals(RuntimeLimits.DEFAULT_MAX_SANDBOXES_PER_POD, limits.maxSandboxesPerPod);
        assertEquals(RuntimeLimits.DEFAULT_SPAN_INGEST_QUEUE_CAPACITY, limits.spanIngestQueueCapacity);
    }

    @Test
    void clampsOutOfRangeValuesAndKeepsExplicitlyUnlimitedOnes() {
        var entity = new SystemSettings();
        entity.runtimeAdmissionEnabled = Boolean.TRUE;
        entity.runtimeHeapWatermarkPercent = 120;
        entity.runtimeMaxSessionsPerUser = 0;
        entity.runtimeMaxInFlightTurnsGlobal = 7;
        entity.runtimeTurnTimeoutSeconds = 0;

        var limits = RuntimeLimits.resolve(entity);
        assertTrue(limits.admissionEnabled);
        assertEquals(99, limits.heapWatermarkPercent);
        assertEquals(RuntimeLimits.DEFAULT_MAX_SESSIONS_PER_USER, limits.maxSessionsPerUser);
        assertEquals(7, limits.maxInFlightTurnsGlobal);
        assertEquals(0, limits.turnTimeoutSeconds);
    }

    @Test
    void fingerprintTracksEveryLimitSoTheCacheRebuilds() {
        assertEquals("", RuntimeLimits.fingerprint(null));
        var changed = new SystemSettings();
        changed.runtimeMaxSandboxesPerPod = 42;
        assertNotEquals(RuntimeLimits.fingerprint(new SystemSettings()), RuntimeLimits.fingerprint(changed));
    }
}
