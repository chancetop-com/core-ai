package ai.core.server.web.sse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner-pod registry of session event hubs.
 *
 * @author stephen
 */
public class SessionEventHubRegistry {
    private final Map<String, SessionEventHub> hubs = new ConcurrentHashMap<>();

    public SessionEventHub get(String sessionId) {
        return hubs.get(sessionId);
    }

    public SessionEventHub getOrCreate(String sessionId) {
        return hubs.computeIfAbsent(sessionId, SessionEventHub::new);
    }

    public void remove(String sessionId) {
        var hub = hubs.remove(sessionId);
        if (hub != null) hub.close();
    }

    public long bufferedBytes() {
        long total = 0;
        for (var hub : hubs.values()) total += hub.stats().bufferedBytes();
        return total;
    }

    public long bufferedEvents() {
        long total = 0;
        for (var hub : hubs.values()) total += hub.stats().bufferedEvents();
        return total;
    }

    public long droppedTotal() {
        long total = 0;
        for (var hub : hubs.values()) total += hub.stats().droppedEvents();
        return total;
    }

    public int size() {
        return hubs.size();
    }
}
