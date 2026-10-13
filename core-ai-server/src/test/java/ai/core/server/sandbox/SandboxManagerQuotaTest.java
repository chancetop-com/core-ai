package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.runtime.RuntimeLimits;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sandbox budgets: they must refuse before anything is provisioned, so a saturated pod shows up as a
 * clear tool error instead of an unbounded pile of pods.
 *
 * @author stephen
 */
class SandboxManagerQuotaTest {
    private final RuntimeLimits limits = new RuntimeLimits();
    private final AtomicInteger ids = new AtomicInteger();

    @Test
    void refusesWhenThePodBudgetIsReached() {
        limits.maxSandboxesPerPod = 1;
        var manager = manager(provider());

        manager.acquire(config(512), "s1", "u1");
        var refused = assertThrows(SandboxQuotaExceededException.class, () -> manager.acquire(config(512), "s2", "u1"));

        assertTrue(refused.getMessage().contains("capacity reached"));
        assertEquals(1, manager.activeCount());
        assertEquals(1, manager.rejectedTotal());
    }

    @Test
    void refusesWhenTheUserAlreadyHoldsEnoughSandboxes() {
        limits.maxSandboxesPerUser = 1;
        var manager = manager(provider());

        manager.acquire(config(512), "s1", "u1");
        var refused = assertThrows(SandboxQuotaExceededException.class, () -> manager.acquire(config(512), "s2", "u1"));

        assertTrue(refused.getMessage().contains("sandbox limit reached for this user"));
        // another user is unaffected by the first user's budget
        manager.acquire(config(512), "s3", "u2");
        assertEquals(2, manager.activeCount());
    }

    @Test
    void refusesWhenTheUserMemoryBudgetWouldBeExceeded() {
        limits.maxSandboxMemoryMbPerUser = 1024;
        var manager = manager(provider());

        manager.acquire(config(512), "s1", "u1");
        manager.acquire(config(512), "s2", "u1");
        var refused = assertThrows(SandboxQuotaExceededException.class, () -> manager.acquire(config(512), "s3", "u1"));

        assertTrue(refused.getMessage().contains("memory budget reached"));
    }

    @Test
    void releasingReturnsTheBudget() {
        limits.maxSandboxesPerPod = 1;
        var manager = manager(provider());

        var first = manager.acquire(config(512), "s1", "u1");
        manager.release(first);
        manager.acquire(config(512), "s2", "u1");

        assertEquals(1, manager.activeCount());
        assertEquals(0, manager.rejectedTotal());
    }

    @Test
    void attachAdoptsAnExistingSandboxWithoutChargingTheBudget() {
        limits.maxSandboxesPerPod = 1;
        var provider = provider();
        var attached = sandbox();
        when(provider.attach(any(), any(), any(), any())).thenReturn(Optional.of(attached));
        var manager = manager(provider);

        manager.acquire(config(512), "s1", "u1");
        assertTrue(manager.attach("claim-existing", config(512), "s1", "u1").isPresent());
        assertEquals(2, manager.activeCount());
    }

    private SandboxManager manager(SandboxProvider provider) {
        var manager = new SandboxManager(provider);
        manager.limitsSupplier = () -> limits;
        return manager;
    }

    private SandboxProvider provider() {
        var provider = mock(SandboxProvider.class);
        when(provider.acquire(any(), any(), any())).thenAnswer(invocation -> sandbox());
        return provider;
    }

    private Sandbox sandbox() {
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("sb-" + ids.incrementAndGet());
        return sandbox;
    }

    private SandboxConfig config(int memoryMb) {
        var config = new SandboxConfig();
        config.memoryLimitMb = memoryMb;
        return config;
    }
}
