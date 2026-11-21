package ai.core.server.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionOwnershipRegistryTest {
    @Test
    void cachedOwnershipAvoidsRepeatedStoreLookups() {
        var store = new CountingStore();
        var registry = new SessionOwnershipRegistry(store);
        store.ownerPod = registry.getHostname();

        assertTrue(registry.isOwnerCached("s-1"));
        assertTrue(registry.isOwnerCached("s-1"));
        assertEquals(1, store.ownerCalls);
    }

    @Test
    void claimRefreshesTheCache() {
        var store = new CountingStore();
        var registry = new SessionOwnershipRegistry(store);
        store.claimResult = true;

        assertTrue(registry.claim("s-1"));
        assertTrue(registry.isOwnerCached("s-1"));
        assertEquals(0, store.ownerCalls);
    }

    @Test
    void failedRenewDropsTheCache() {
        var store = new CountingStore();
        var registry = new SessionOwnershipRegistry(store);
        store.ownerPod = registry.getHostname();
        assertTrue(registry.isOwnerCached("s-1"));

        store.ownerPod = "other-pod";
        store.renewResult = false;
        assertFalse(registry.renew("s-1"));
        assertFalse(registry.isOwnerCached("s-1"));
        assertEquals(2, store.ownerCalls);
    }

    private static final class CountingStore implements OwnershipStore {
        private String ownerPod;
        private boolean claimResult;
        private boolean renewResult = true;
        private int ownerCalls;

        @Override
        public boolean claim(String resourceId, String ownerPod) {
            return claimResult;
        }

        @Override
        public boolean renew(String resourceId, String ownerPod) {
            return renewResult && ownerPod.equals(this.ownerPod);
        }

        @Override
        public String owner(String resourceId) {
            ownerCalls++;
            return ownerPod;
        }

        @Override
        public void release(String resourceId, String ownerPod) {
            if (ownerPod.equals(this.ownerPod)) this.ownerPod = null;
        }
    }
}
