package ai.core.server.session;

import ai.core.session.InProcessAgentSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-pod session lifecycle counters and footprint snapshots for the runtime stats job.
 *
 * @author stephen
 */
public class SessionRuntimeStats {
    private final ConcurrentMap<String, InProcessAgentSession> sessions;
    private final ConcurrentMap<String, Long> lastActivity;
    private final ConcurrentMap<String, Long> residentSince = new ConcurrentHashMap<>(16);
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong closed = new AtomicLong();
    private final AtomicLong idleEvicted = new AtomicLong();

    public SessionRuntimeStats(ConcurrentMap<String, InProcessAgentSession> sessions, ConcurrentMap<String, Long> lastActivity) {
        this.sessions = sessions;
        this.lastActivity = lastActivity;
    }

    public void onCreated(String sessionId) {
        residentSince.put(sessionId, System.currentTimeMillis());
        created.incrementAndGet();
    }

    public void onClosed(String sessionId, boolean sessionPresent) {
        residentSince.remove(sessionId);
        if (sessionPresent) closed.incrementAndGet();
    }

    public void onIdleEvicted() {
        idleEvicted.incrementAndGet();
    }

    public List<SessionSnapshot> snapshots() {
        var now = System.currentTimeMillis();
        var snapshots = new ArrayList<SessionSnapshot>(sessions.size());
        for (var entry : sessions.entrySet()) {
            var last = lastActivity.get(entry.getKey());
            var resident = residentSince.get(entry.getKey());
            snapshots.add(new SessionSnapshot(entry.getKey(), entry.getValue().footprint.footprint(),
                    last == null ? 0 : Math.max(0, now - last),
                    resident == null ? 0 : Math.max(0, now - resident)));
        }
        return snapshots;
    }

    public long createdTotal() {
        return created.get();
    }

    public long closedTotal() {
        return closed.get();
    }

    public long idleEvictedTotal() {
        return idleEvicted.get();
    }
}
