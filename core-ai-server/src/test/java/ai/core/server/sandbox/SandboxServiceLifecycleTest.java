package ai.core.server.sandbox;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SandboxServiceLifecycleTest {
    @Test
    void providerBackedServiceRegistersCleanupSchedulerAndShutsItDown() throws Exception {
        var provider = mock(SandboxProvider.class);
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), eq(5L), eq(5L), eq(TimeUnit.MINUTES))).thenReturn(null);
        var service = new SandboxService(provider, enabledConfig(), null, nullDependencies(), scheduler);

        verify(scheduler).scheduleAtFixedRate(any(SandboxCleanupJob.class), eq(5L), eq(5L), eq(TimeUnit.MINUTES));

        service.shutdown();

        verify(scheduler).shutdown();
        verify(scheduler).awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void disabledServiceDoesNotCreateSchedulerAndShutdownIsNoOp() {
        var service = new SandboxService();

        service.shutdown();

        assertFalse(service.isSandboxEnabled(enabledConfig()));
    }

    @Test
    void invalidatingSandboxBindingMakesLegacyMappingUnavailableToNextRebuild() {
        var jedisPool = mock(JedisPool.class);
        var jedis = mock(Jedis.class);
        var binding = new AtomicReference<>("legacy-sandbox");
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.get("sandbox:legacy-session")).thenAnswer(invocation -> binding.get());
        when(jedis.del("sandbox:legacy-session")).thenAnswer(invocation -> {
            binding.set(null);
            return 1L;
        });
        var service = new SandboxService(jedisPool, null, null, null);

        assertEquals("legacy-sandbox", service.getSandboxId("legacy-session"));
        service.invalidateSandboxBinding("legacy-session");

        assertNull(service.getSandboxId("legacy-session"));
        verify(jedis).del("sandbox:legacy-session");
    }

    @Test
    void invalidatingSandboxBindingPropagatesRedisDeletionFailure() {
        var jedisPool = mock(JedisPool.class);
        var jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.del("sandbox:legacy-session")).thenThrow(new IllegalStateException("redis down"));
        var service = new SandboxService(jedisPool, null, null, null);

        assertThrows(IllegalStateException.class,
                () -> service.invalidateSandboxBinding("legacy-session"));
    }

    @Test
    void reattachAdoptsNewSnapshotEpochWithoutRestore() {
        var provider = mock(SandboxProvider.class);
        var attached = readySandbox("sandbox-1");
        when(provider.attach(eq("sandbox-1"), any(), eq("session-1"), eq("user-1")))
                .thenReturn(java.util.Optional.of(attached));
        var snapshotService = mock(SandboxSnapshotService.class);
        when(snapshotService.beginEpoch("session-1")).thenReturn(7L);
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(null);
        var service = new SandboxService(provider, enabledConfig(), null,
                new SandboxServiceDependencies(null, snapshotService, null, null, null), scheduler);

        var result = (LazySandbox) service.reattachOrCreateSandbox(
                "sandbox-1", enabledConfig(), "session-1", "user-1", null);

        assertEquals(7L, result.snapshotEpoch());
        assertTrue(result.snapshotCaptureEligible());
        verify(snapshotService).beginEpoch("session-1");
        verify(snapshotService, never()).restoreLatestWithMetadata(any(), any(), any(), anyInt());
    }

    @Test
    void reattachSurvivesSnapshotEpochAllocationFailure() {
        var provider = mock(SandboxProvider.class);
        var attached = readySandbox("sandbox-1");
        when(provider.attach(eq("sandbox-1"), any(), eq("session-1"), eq("user-1")))
                .thenReturn(java.util.Optional.of(attached));
        var snapshotService = mock(SandboxSnapshotService.class);
        when(snapshotService.beginEpoch("session-1"))
                .thenThrow(new IllegalStateException("mongo unavailable"));
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(null);
        var service = new SandboxService(provider, enabledConfig(), null,
                new SandboxServiceDependencies(null, snapshotService, null, null, null), scheduler);

        var result = (LazySandbox) service.reattachOrCreateSandbox(
                "sandbox-1", enabledConfig(), "session-1", "user-1", null);

        assertEquals(0L, result.snapshotEpoch());
        assertFalse(result.snapshotCaptureEligible());
    }

    private Sandbox readySandbox(String id) {
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn(id);
        when(sandbox.getStatus()).thenReturn(SandboxStatus.READY);
        return sandbox;
    }

    private SandboxConfig enabledConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        return config;
    }

    private SandboxServiceDependencies nullDependencies() {
        return new SandboxServiceDependencies(null, null, null, null, null);
    }
}
