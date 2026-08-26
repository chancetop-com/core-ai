package ai.core.agent;

import ai.core.persistence.PersistenceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the cancel-turn persistence race: cancelTurn() saves from the caller thread while the
 * session thread saves the cancelled turn. Snapshot + write must not interleave, or a stale snapshot
 * taken before the partial assistant message was added overwrites the complete one.
 */
class NodeSaveConcurrencyTest {
    @Test
    void concurrentSavesDoNotInterleave() throws InterruptedException {
        var provider = new BlockingPersistenceProvider();
        var agent = Agent.builder()
                .llmProvider(new AgentPersistenceTest.SummaryLLMProvider("unused"))
                .persistenceProvider(provider)
                .build();

        var first = Thread.ofPlatform().start(() -> agent.save("session"));
        assertTrue(provider.firstEntered.await(5, TimeUnit.SECONDS), "first save should reach the provider");

        var second = Thread.ofPlatform().start(() -> agent.save("session"));
        assertFalse(provider.secondEntered.await(300, TimeUnit.MILLISECONDS),
                "second save must wait until the first snapshot has been written");

        provider.release.countDown();
        first.join(5_000);
        second.join(5_000);
        assertEquals(2, provider.saves.get());
    }

    static class BlockingPersistenceProvider implements PersistenceProvider {
        final AtomicInteger saves = new AtomicInteger();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void save(String id, String context) {
            if (saves.incrementAndGet() == 1) {
                firstEntered.countDown();
                awaitRelease();
            } else {
                secondEntered.countDown();
            }
        }

        private void awaitRelease() {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void clear() {
        }

        @Override
        public void delete(List<String> ids) {
        }

        @Override
        public Optional<String> load(String id) {
            return Optional.empty();
        }
    }
}
