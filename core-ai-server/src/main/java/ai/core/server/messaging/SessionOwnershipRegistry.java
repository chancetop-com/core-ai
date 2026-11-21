package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
public class SessionOwnershipRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionOwnershipRegistry.class);
    // event publishing asks ownership for every chunk; a short lease cache keeps that off the database
    private static final long OWNER_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_OWNER_CACHE_ENTRIES = 20_000;

    private final OwnershipStore store;
    private final String hostname;
    private final Map<String, CachedOwner> ownerCache = new ConcurrentHashMap<>();

    @SuppressFBWarnings("ITU_INAPPROPRIATE_TOSTRING_USE")
    public SessionOwnershipRegistry(OwnershipStore store) {
        this.store = store;
        this.hostname = resolveHostname();
        LOGGER.info("SessionOwnershipRegistry initialized, hostname={}", hostname);
    }

    public boolean claim(String sessionId) {
        var claimed = store.claim(sessionId, hostname);
        if (claimed) {
            cacheOwner(sessionId, true);
            LOGGER.debug("claimed session ownership, sessionId={}", sessionId);
        } else {
            ownerCache.remove(sessionId);
        }
        return claimed;
    }

    public boolean renew(String sessionId) {
        var renewed = store.renew(sessionId, hostname);
        if (renewed) {
            cacheOwner(sessionId, true);
        } else {
            ownerCache.remove(sessionId);
        }
        return renewed;
    }

    public boolean claimOrRenew(String sessionId) {
        if (renew(sessionId)) return true;
        return claim(sessionId);
    }

    public String getOwner(String sessionId) {
        return store.owner(sessionId);
    }

    public boolean isOwner(String sessionId) {
        var owner = hostname.equals(getOwner(sessionId));
        cacheOwner(sessionId, owner);
        return owner;
    }

    /**
     * Fast ownership check for the event hot path. The answer is cached for a short lease so a
     * streaming turn does not query PostgreSQL for every chunk; claim/renew/release update the
     * cache immediately.
     */
    public boolean isOwnerCached(String sessionId) {
        var cached = ownerCache.get(sessionId);
        if (cached != null && System.nanoTime() - cached.checkedAtNanos < OWNER_CACHE_TTL_NANOS) {
            return cached.owner;
        }
        return isOwner(sessionId);
    }

    public void release(String sessionId) {
        store.release(sessionId, hostname);
        ownerCache.remove(sessionId);
        LOGGER.debug("released session ownership, sessionId={}", sessionId);
    }

    public String getHostname() {
        return hostname;
    }

    private void cacheOwner(String sessionId, boolean owner) {
        if (ownerCache.size() >= MAX_OWNER_CACHE_ENTRIES) ownerCache.clear();
        ownerCache.put(sessionId, new CachedOwner(owner, System.nanoTime()));
    }

    @SuppressFBWarnings("ITU_INAPPROPRIATE_TOSTRING_USE")
    private String resolveHostname() {
        var env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private record CachedOwner(boolean owner, long checkedAtNanos) {
    }
}
