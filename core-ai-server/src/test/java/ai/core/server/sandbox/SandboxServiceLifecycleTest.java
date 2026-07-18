package ai.core.server.sandbox;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private SandboxConfig enabledConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        return config;
    }

    private SandboxServiceDependencies nullDependencies() {
        return new SandboxServiceDependencies(null, null, null, null);
    }
}
