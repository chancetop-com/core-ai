package ai.core.server.quality;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-instance admission for quality runs. This deliberately does not pretend to be cluster-wide; it stops
 * one pod from exhausting its own model capacity, which is the failure mode an external HTTP admission
 * interceptor does not cover.
 *
 * @author core-ai
 */
public class QualityConcurrencyGuard {
    static final int MAX_GLOBAL = 6;
    static final int MAX_PER_ACTOR = 2;
    static final int MAX_PER_AGENT = 1;

    private final AtomicInteger global = new AtomicInteger();
    private final Map<String, AtomicInteger> actors = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> agents = new ConcurrentHashMap<>();

    public Lease acquire(String agentId, String actor) {
        if (!tryAcquire(global, MAX_GLOBAL)) throw QualityGateException.capacity(30);
        if (!tryAcquire(actors.computeIfAbsent(actor, ignored -> new AtomicInteger()), MAX_PER_ACTOR)) {
            release(global);
            throw QualityGateException.capacity(20);
        }
        if (!tryAcquire(agents.computeIfAbsent(agentId, ignored -> new AtomicInteger()), MAX_PER_AGENT)) {
            release(actors.get(actor));
            release(global);
            throw QualityGateException.capacity(10);
        }
        return new Lease(agentId, actor);
    }

    private boolean tryAcquire(AtomicInteger counter, int max) {
        while (true) {
            int current = counter.get();
            if (current >= max) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    private void release(AtomicInteger counter) {
        if (counter != null) counter.decrementAndGet();
    }

    public final class Lease implements AutoCloseable {
        private final String agentId;
        private final String actor;
        private boolean closed;

        private Lease(String agentId, String actor) {
            this.agentId = agentId;
            this.actor = actor;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            release(agents.get(agentId));
            release(actors.get(actor));
            release(global);
        }
    }
}
