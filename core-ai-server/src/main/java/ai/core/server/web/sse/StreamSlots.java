package ai.core.server.web.sse;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-Pod cap on concurrently bridged terminal SSE streams. Each core-ai-server
 * Pod independently bounds its own bridged connections (design doc: "each server
 * Pod enforces a configurable cap on concurrently bridged terminal streams,
 * default 50; above the cap, stream connection returns 429") -- a 2-replica
 * deployment therefore admits up to {@code replicas * max} streams cluster-wide.
 *
 * @author xander
 */
public class StreamSlots {
    private final int max;
    private final AtomicInteger active = new AtomicInteger();

    public StreamSlots(int max) {
        this.max = max;
    }

    /**
     * Reserves one slot.
     *
     * @return true if a slot was reserved (caller must eventually call {@link #release()});
     * false if the cap is already reached (caller must NOT call {@link #release()})
     */
    public boolean acquire() {
        if (active.incrementAndGet() > max) {
            active.decrementAndGet();
            return false;
        }
        return true;
    }

    public void release() {
        active.decrementAndGet();
    }
}
