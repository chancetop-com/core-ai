package ai.core.server.web.sse;

import ai.core.server.messaging.OwnershipStore;
import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.sse.SsePreflight;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalSsePreflightTest {
    private final PodRegistry podRegistry = new PodRegistry("pod-a", "10.0.0.1:8080");
    private final LocalOwnershipStore store = new LocalOwnershipStore();
    private final SessionOwnershipRegistry ownership = new SessionOwnershipRegistry(store);

    @Test
    void externalHostGets404() {
        var preflight = preflight();
        var result = preflight.check(request("evil.example.com", "token", "s-1"));
        assertEquals(404, result.status());
    }

    @Test
    void badTokenGets401() {
        var preflight = preflight();
        var result = preflight.check(request("10.0.0.1", "wrong", "s-1"));
        assertEquals(401, result.status());
    }

    @Test
    void nonOwnerGets409() {
        store.owner = "pod-b";
        var preflight = preflight();
        var result = preflight.check(request("10.0.0.1", "token", "s-1"));
        assertEquals(409, result.status());
    }

    @Test
    void ownerGets200() {
        store.owner = ownership.getHostname();
        var preflight = preflight();
        var result = preflight.check(request("10.0.0.1", "token", "s-1"));
        assertEquals(200, result.status());
    }

    private InternalSsePreflight preflight() {
        return new InternalSsePreflight(podRegistry, ownership, "token", "");
    }

    private SsePreflight.Request request(String host, String token, String sessionId) {
        return new SsePreflight.Request("GET", "/_internal/sessions/events", host,
                Map.of("X-Internal-Token", token), Map.of("session_id", sessionId, "after", "0"));
    }

    private static final class LocalOwnershipStore implements OwnershipStore {
        String owner = "pod-a";

        @Override
        public boolean claim(String resourceId, String ownerPod) {
            owner = ownerPod;
            return true;
        }

        @Override
        public boolean renew(String resourceId, String ownerPod) {
            return owner != null && owner.equals(ownerPod);
        }

        @Override
        public String owner(String resourceId) {
            return owner;
        }

        @Override
        public void release(String resourceId, String ownerPod) {
            if (owner != null && owner.equals(ownerPod)) owner = null;
        }
    }
}
