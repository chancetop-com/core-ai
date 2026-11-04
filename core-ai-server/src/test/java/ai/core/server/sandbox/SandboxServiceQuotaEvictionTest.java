package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.runtime.RuntimeLimits;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quota eviction: a user at the sandbox budget gets its least recently used idle sandbox reclaimed
 * instead of being refused the next one, while persistent, busy and just-used sessions stay untouched.
 *
 * @author stephen
 */
class SandboxServiceQuotaEvictionTest {
    private final RuntimeLimits limits = new RuntimeLimits();
    private final AtomicInteger ids = new AtomicInteger();

    @Test
    void reclaimsAnIdleSessionsSandboxAndLetsItColdStartLater() {
        limits.maxSandboxesPerUser = 1;
        var provider = provider();
        var service = service(provider, null);

        var first = (LazySandbox) service.createSessionSandbox(config(), "s1", "u1", null);
        first.ensureReady();
        var second = (LazySandbox) service.createSessionSandbox(config(), "s2", "u1", null);

        second.ensureReady();

        assertEquals(SandboxStatus.TERMINATED, first.getStatus());
        assertEquals(1, service.sandboxManager().activeCount());
        assertEquals(1, service.sandboxManager().evictedTotal());
        assertEquals(SandboxStatus.READY, second.getStatus());

        // the evicted session is not broken: its next tool call acquires a new sandbox, evicting the
        // now-idle second one through the same path
        first.ensureReady();

        assertEquals(SandboxStatus.READY, first.getStatus());
        assertEquals(SandboxStatus.TERMINATED, second.getStatus());
        assertEquals(1, service.sandboxManager().activeCount());
        assertEquals(2, service.sandboxManager().evictedTotal());
    }

    @Test
    void neverReclaimsAPersistentSandbox() {
        limits.maxSandboxesPerUser = 1;
        var service = service(provider(), null);

        ((LazySandbox) service.createSandbox(config(), "s1", "system", true)).ensureReady();
        var second = (LazySandbox) service.createSessionSandbox(config(), "s2", "system", null);

        assertThrows(SandboxQuotaExceededException.class, second::ensureReady);

        assertEquals(1, service.sandboxManager().activeCount());
        assertEquals(SandboxStatus.READY, service.getSandbox("s1").getStatus());
    }

    @Test
    void neverReclaimsASessionWhoseTurnIsRunning() {
        limits.maxSandboxesPerUser = 1;
        var service = service(provider(), null);
        service.sessionBusy = "s1"::equals;

        ((LazySandbox) service.createSessionSandbox(config(), "s1", "u1", null)).ensureReady();
        var second = (LazySandbox) service.createSessionSandbox(config(), "s2", "u1", null);

        assertThrows(SandboxQuotaExceededException.class, second::ensureReady);

        assertEquals(1, service.sandboxManager().activeCount());
        assertEquals(SandboxStatus.READY, service.getSandbox("s1").getStatus());
    }

    @Test
    void neverReclaimsASandboxUsedWithinTheIdleWindow() {
        limits.maxSandboxesPerUser = 1;
        var service = service(provider(), null);
        service.evictionMinIdleSeconds = 600;

        ((LazySandbox) service.createSessionSandbox(config(), "s1", "u1", null)).ensureReady();
        var second = (LazySandbox) service.createSessionSandbox(config(), "s2", "u1", null);

        assertThrows(SandboxQuotaExceededException.class, second::ensureReady);

        assertEquals(1, service.sandboxManager().activeCount());
    }

    @Test
    void keepsTheSandboxWhenItsSnapshotCannotBeCaptured() {
        limits.maxSandboxesPerUser = 1;
        var provider = provider();
        var snapshotService = mock(SandboxSnapshotService.class);
        when(snapshotService.enabled()).thenReturn(Boolean.TRUE);
        when(snapshotService.beginEpoch(any())).thenReturn(1L);
        when(snapshotService.restoreLatestWithMetadata(any(), any(), any(), anyInt()))
                .thenReturn(new SandboxSnapshotService.RestoreResult(SandboxSnapshotService.RestoreOutcome.NONE, null));
        when(snapshotService.captureBeforeRelease(any(), any(), anyLong(), any(), anyInt(), any()))
                .thenReturn(Boolean.FALSE);
        var service = service(provider, snapshotService);

        var first = (LazySandbox) service.createSessionSandbox(config(), "s1", "u1", null);
        first.ensureReady();
        first.uploadFile("/tmp/changed.txt", new byte[]{1});
        var second = (LazySandbox) service.createSessionSandbox(config(), "s2", "u1", null);

        assertThrows(SandboxQuotaExceededException.class, second::ensureReady);

        assertEquals(1, service.sandboxManager().activeCount());
        assertEquals(0, service.sandboxManager().evictedTotal());
        assertEquals(SandboxStatus.READY, first.getStatus());
    }

    private SandboxService service(SandboxProvider provider, SandboxSnapshotService snapshotService) {
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(null);
        var service = new SandboxService(provider, config(), null,
                new SandboxServiceDependencies(null, snapshotService, null, null, null), scheduler);
        service.evictionMinIdleSeconds = 0;
        service.sandboxManager().limitsSupplier = () -> limits;
        return service;
    }

    private SandboxProvider provider() {
        var provider = mock(SandboxProvider.class);
        when(provider.acquire(any(), any(), any())).thenAnswer(invocation -> {
            var sandbox = mock(Sandbox.class);
            when(sandbox.getId()).thenReturn("sb-" + ids.incrementAndGet());
            when(sandbox.getStatus()).thenReturn(SandboxStatus.READY);
            when(sandbox.ip()).thenReturn("10.0.0.1");
            when(sandbox.port()).thenReturn(8080);
            when(sandbox.image()).thenReturn("img:latest");
            return sandbox;
        });
        return provider;
    }

    private SandboxConfig config() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        config.memoryLimitMb = 512;
        return config;
    }
}
