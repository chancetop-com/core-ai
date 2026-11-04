package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.runtime.RuntimeLimits;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
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

    @Test
    void evictsTheLeastRecentlyUsedSandboxOfTheRequestingUser() {
        limits.maxSandboxesPerUser = 2;
        var manager = manager(provider());
        manager.acquire(config(512), "s1", "u1");
        manager.acquire(config(512), "s2", "u1");
        older(manager, "s1", 600);

        var evicted = new ArrayList<String>();
        manager.evictionPolicy = evictingPolicy(manager, evicted);

        manager.acquire(config(512), "s3", "u1");

        assertEquals(List.of("s1"), evicted);
        assertEquals(2, manager.activeCount());
    }

    @Test
    void touchMovesASandboxOutOfTheEvictionPath() {
        limits.maxSandboxesPerUser = 2;
        var manager = manager(provider());
        manager.acquire(config(512), "s1", "u1");
        var second = manager.acquire(config(512), "s2", "u1");
        older(manager, "s1", 600);
        older(manager, "s2", 600);
        manager.touch(second.getId());

        var evicted = new ArrayList<String>();
        manager.evictionPolicy = evictingPolicy(manager, evicted);

        manager.acquire(config(512), "s3", "u1");

        assertEquals(List.of("s1"), evicted);
    }

    @Test
    void neverEvictsAnotherUsersSandbox() {
        limits.maxSandboxesPerUser = 1;
        var manager = manager(provider());
        manager.acquire(config(512), "s1", "u1");
        manager.acquire(config(512), "s2", "u2");

        var evicted = new ArrayList<String>();
        manager.evictionPolicy = evictingPolicy(manager, evicted);

        manager.acquire(config(512), "s3", "u1");

        assertEquals(List.of("s1"), evicted);
        assertEquals(2, manager.activeCount());
    }

    @Test
    void refusesWithoutEvictingWhenTheRequestedSandboxAloneExceedsTheBudget() {
        limits.maxSandboxMemoryMbPerUser = 1024;
        var manager = manager(provider());
        manager.acquire(config(512), "s1", "u1");
        manager.evictionPolicy = evictingPolicy(manager, new ArrayList<>());

        assertThrows(SandboxQuotaExceededException.class, () -> manager.acquire(config(2048), "s2", "u1"));

        assertEquals(1, manager.activeCount());
        assertEquals(0, manager.evictedTotal());
    }

    @Test
    void refusesWhenNoCandidateCanBeEvicted() {
        limits.maxSandboxesPerUser = 1;
        var manager = manager(provider());
        manager.acquire(config(512), "s1", "u1");
        manager.evictionPolicy = new SandboxManager.EvictionPolicy() {
            @Override
            public boolean isEvictable(SandboxManager.SandboxEntry entry, Instant now) {
                return false;
            }

            @Override
            public boolean evict(SandboxManager.SandboxEntry entry) {
                throw new AssertionError("must not evict a rejected candidate");
            }
        };

        assertThrows(SandboxQuotaExceededException.class, () -> manager.acquire(config(512), "s2", "u1"));

        assertEquals(1, manager.activeCount());
        assertEquals(1, manager.rejectedTotal());
        assertEquals(0, manager.evictedTotal());
    }

    @Test
    void concurrentAcquiresCannotOvershootTheUserBudget() throws Exception {
        limits.maxSandboxesPerUser = 1;
        var manager = manager(provider());
        var start = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            var sessionId = "s" + i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    if (acquireOne(manager, sessionId, start)) admitted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (var thread : threads) {
            thread.join();
        }

        assertEquals(1, admitted.get());
        assertEquals(1, manager.activeCount());
        assertEquals(3, manager.rejectedTotal());
    }

    private boolean acquireOne(SandboxManager manager, String sessionId, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            manager.acquire(config(512), sessionId, "u1");
            return true;
        } catch (SandboxQuotaExceededException e) {
            return false;
        }
    }

    private SandboxManager.EvictionPolicy evictingPolicy(SandboxManager manager, List<String> evicted) {
        return new SandboxManager.EvictionPolicy() {
            @Override
            public boolean isEvictable(SandboxManager.SandboxEntry entry, Instant now) {
                return true;
            }

            @Override
            public boolean evict(SandboxManager.SandboxEntry entry) {
                evicted.add(entry.sessionId);
                manager.release(entry.sandbox);
                return true;
            }
        };
    }

    private void older(SandboxManager manager, String sessionId, long seconds) {
        manager.getActiveSandboxes().stream()
                .filter(entry -> sessionId.equals(entry.sessionId))
                .forEach(entry -> entry.lastAccessAt = Instant.now().minusSeconds(seconds));
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
